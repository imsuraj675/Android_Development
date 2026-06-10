package com.example.sender

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

class CertificateManager(private val filesDir: File) {

    companion object {
        private const val TAG = "CertManager"
        const val KEYSTORE_PASSWORD = "sender_ks"
        const val SERVER_KEY_ALIAS  = "server"
        private const val KEY_ALGORITHM   = "EC"
        private const val KEY_CURVE       = "secp256r1"
        private const val SIGN_ALGORITHM  = "SHA256withECDSA"
        private const val CA_KEY_FILE     = "sender_ca.key"
        private const val CA_CERT_FILE    = "sender_ca.crt"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a PKCS12 KeyStore containing the server cert chain.
     * Always regenerates the server cert (CA is reused if it already exists).
     * Called once at server start.
     */
    fun buildKeyStore(ips: List<String>): KeyStore {
        val (caKey, caCert) = getOrCreateCA()
        return buildServerKeyStore(caKey, caCert, ips)
    }

    /** PEM bytes of the CA cert for the /ca.crt download endpoint. */
    fun getCACertPem(): ByteArray? =
        File(filesDir, CA_CERT_FILE).takeIf { it.exists() }?.readBytes()

    // ── CA management ─────────────────────────────────────────────────────────

    private fun getOrCreateCA(): Pair<PrivateKey, X509Certificate> {
        val keyFile  = File(filesDir, CA_KEY_FILE)
        val certFile = File(filesDir, CA_CERT_FILE)
        if (keyFile.exists() && certFile.exists()) {
            return try {
                loadPrivateKey(keyFile) to loadCert(certFile)
            } catch (e: Exception) {
                Log.w(TAG, "Existing CA unreadable — regenerating", e)
                generateCA()
            }
        }
        return generateCA()
    }

    private fun generateCA(): Pair<PrivateKey, X509Certificate> {
        Log.i(TAG, "Generating new CA key pair")
        val kp = newKeyPair()
        val subject = X500Name("CN=Sender Local CA,O=Sender App")
        val notBefore = Date()
        val notAfter  = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))

        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE, notBefore, notAfter, subject, kp.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))

        val cert = sign(builder, kp.private)

        persistPrivateKey(kp.private, File(filesDir, CA_KEY_FILE))
        persistCert(cert, File(filesDir, CA_CERT_FILE))
        Log.i(TAG, "CA generated and persisted")
        return kp.private to cert
    }

    // ── Server cert ───────────────────────────────────────────────────────────

    private fun buildServerKeyStore(
        caKey: PrivateKey,
        caCert: X509Certificate,
        ips: List<String>
    ): KeyStore {
        Log.i(TAG, "Generating server cert — SANs: ${ips.joinToString()}")
        val kp       = newKeyPair()
        val issuer   = X500Name(caCert.subjectX500Principal.name)
        val subject  = X500Name("CN=Sender Server,O=Sender App")
        val notBefore = Date()
        val notAfter  = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(730))
        val serial    = BigInteger(64, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, kp.public
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))

        // SubjectAlternativeNames — IP + mDNS hostnames
        val sans = mutableListOf<GeneralName>().apply {
            add(GeneralName(GeneralName.dNSName, "phone.local"))
            add(GeneralName(GeneralName.dNSName, "sender.local"))
            add(GeneralName(GeneralName.dNSName, "localhost"))
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            ips.forEach { add(GeneralName(GeneralName.iPAddress, it)) }
        }
        builder.addExtension(Extension.subjectAlternativeName, false,
            GeneralNames(sans.toTypedArray()))

        val serverCert = sign(builder, caKey)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(
            SERVER_KEY_ALIAS,
            kp.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(serverCert, caCert)  // chain: server → CA
        )
        Log.i(TAG, "Server keystore ready")
        return ks
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun newKeyPair() = KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
        initialize(ECGenParameterSpec(KEY_CURVE), SecureRandom())
    }.generateKeyPair()

    private fun sign(
        builder: JcaX509v3CertificateBuilder,
        signingKey: PrivateKey
    ): X509Certificate {
        val signer = JcaContentSignerBuilder(SIGN_ALGORITHM).build(signingKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun persistPrivateKey(key: PrivateKey, file: File) {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        file.writeText("-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n")
    }

    private fun loadPrivateKey(file: File): PrivateKey {
        val pem = file.readText()
        val b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        val der = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun persistCert(cert: X509Certificate, file: File) {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        file.writeText("-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n")
    }

    private fun loadCert(file: File): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(file.inputStream()) as X509Certificate
}
