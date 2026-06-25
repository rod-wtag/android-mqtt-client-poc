package com.example.poc;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Handles the full dynamic-certificate lifecycle for mTLS:
 *  1. Generate/reuse a hardware-backed (StrongBox/TEE) EC key pair.
 *  2. Build a signed CSR from that key pair.
 *  3. Install a backend-issued certificate chain onto that same key.
 *  4. Build KeyManagerFactory / TrustManagerFactory for the MQTT TLS connection.
 *
 * IMPORTANT: bump KEY_ALIAS any time the KeyGenParameterSpec changes,
 * and uninstall/reinstall the app when doing so, to guarantee no stale
 * key material survives in AndroidKeyStore from a previous test run.
 */
public class MtlsCertificateManager {

    private static final String KEY_ALIAS = "mqtt_mtls_poc_alias_v4";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    // ---------------------------------------------------------------
    // Step 1: Generate (or reuse) the hardware-backed EC key pair
    // ---------------------------------------------------------------
    public static KeyPair getOrCreateHardwareKeyPair() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
            Certificate cert = keyStore.getCertificate(KEY_ALIAS);
            if (privateKey != null && cert != null) {
                Log.d("MQTT", "Reusing existing key pair for alias: " + KEY_ALIAS);
                Log.d("MQTT", "Existing public key: " +
                        Base64.encodeToString(cert.getPublicKey().getEncoded(), Base64.NO_WRAP));
                return new KeyPair(cert.getPublicKey(), privateKey);
            }
            keyStore.deleteEntry(KEY_ALIAS);
        }

        Log.d("MQTT", "Generating fresh key pair for alias: " + KEY_ALIAS);

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);

        KeyGenParameterSpec.Builder specBuilder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"));

        generator.initialize(specBuilder.build());
        KeyPair kp = generator.generateKeyPair();

        Log.d("MQTT", "New public key: " +
                Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP));

        return kp;
    }

    // ---------------------------------------------------------------
    // Step 2: Build a signed CSR PEM for the given key pair
    // ---------------------------------------------------------------
    public static String generateCsrPem(KeyPair keyPair, String deviceId) throws Exception {
        X500Name subject = new X500Name("CN=" + deviceId);

        JcaPKCS10CertificationRequestBuilder csrBuilder =
                new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.getPrivate());

        PKCS10CertificationRequest csr = csrBuilder.build(signer);
        String base64 = Base64.encodeToString(csr.getEncoded(), Base64.NO_WRAP);

        return "-----BEGIN CERTIFICATE REQUEST-----\n" + base64 + "\n-----END CERTIFICATE REQUEST-----";
    }

    // ---------------------------------------------------------------
    // Helper: parse a PEM string into an X509Certificate, sanitizing
    // common copy/paste artifacts (literal "\n", stray "\r", etc).
    // ---------------------------------------------------------------
    static X509Certificate parseCertificate(String pem) throws Exception {
        String cleaned = pem
                .replace("\\n", "\n")
                .replace("\r", "")
                .trim();

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------
    // Diagnostic: verify the chain's internal signatures
    // (leaf signed by intermediate, intermediate signed by root).
    // Does NOT check that the leaf's public key matches your private key
    // — see verifyKeyCertMatch() for that.
    // ---------------------------------------------------------------
    public static void verifyChainIntegrity(String leafCertPem, String intermediateCertPem, String rootCaPem) {
        try {
            X509Certificate leaf = parseCertificate(leafCertPem);
            X509Certificate intermediate = parseCertificate(intermediateCertPem);
            X509Certificate root = parseCertificate(rootCaPem);

            leaf.verify(intermediate.getPublicKey());
            Log.d("MQTT", "Leaf signature verified OK against intermediate");

            intermediate.verify(root.getPublicKey());
            Log.d("MQTT", "Intermediate signature verified OK against root");

        } catch (Exception e) {
            Log.e("MQTT", "Chain verification FAILED", e);
        }
    }

    // ---------------------------------------------------------------
    // Diagnostic: verify the leaf certificate's public key matches the
    // private key CURRENTLY stored under KEY_ALIAS in AndroidKeyStore.
    // Run this before trusting any cert — if this fails, the cert was
    // issued for a different key pair than the one you're signing with.
    // ---------------------------------------------------------------
    public static void verifyKeyCertMatch(String leafCertPem) {
        try {
            X509Certificate leaf = parseCertificate(leafCertPem);

            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);

            Certificate storedCert = keyStore.getCertificate(KEY_ALIAS);
            if (storedCert == null) {
                Log.e("MQTT", "No certificate/key currently stored under alias: " + KEY_ALIAS);
                return;
            }

            PublicKey keystorePublicKey = storedCert.getPublicKey();
            boolean matches = leaf.getPublicKey().equals(keystorePublicKey);

            Log.d("MQTT", "Cert public key matches current keystore key: " + matches);
            if (!matches) {
                Log.e("MQTT", "MISMATCH — this cert was issued for a different key pair " +
                        "than the one currently under alias " + KEY_ALIAS + ". You need a fresh CSR + cert.");
            }
        } catch (Exception e) {
            Log.e("MQTT", "Check failed", e);
        }
    }

    // ---------------------------------------------------------------
    // Step 3: attach the issued chain (leaf + intermediate + root)
    // to the hardware key already sitting under KEY_ALIAS.
    // The private key itself is never touched — only the chain is set.
    // ---------------------------------------------------------------
    public static void installIssuedCertificate(String leafCertPem, String intermediateCertPem, String rootCaPem) throws Exception {
        X509Certificate leaf = parseCertificate(leafCertPem);
        X509Certificate intermediate = parseCertificate(intermediateCertPem);
        X509Certificate root = parseCertificate(rootCaPem);

        Certificate[] chain = new Certificate[] { leaf, intermediate, root };

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        PrivateKey existingPrivateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
        if (existingPrivateKey == null) {
            throw new IllegalStateException("No hardware key found for alias " + KEY_ALIAS
                    + " — call getOrCreateHardwareKeyPair() first.");
        }

        KeyStore.PrivateKeyEntry entry = new KeyStore.PrivateKeyEntry(existingPrivateKey, chain);
        keyStore.setEntry(KEY_ALIAS, entry, null);

        Log.d("MQTT", "Chain installed on alias: " + KEY_ALIAS);
    }

    // ---------------------------------------------------------------
    // Step 4a: KeyManagerFactory backed by the AndroidKeyStore alias
    // (proves client identity to EMQX during the TLS handshake).
    // ---------------------------------------------------------------
    public static KeyManagerFactory buildKeyManagerFactory() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, null); // no password — AndroidKeyStore doesn't use one
        return kmf;
    }

    // ---------------------------------------------------------------
    // Step 4b: TrustManagerFactory built from the backend's root_ca_pem
    // (verifies EMQX's server certificate during the TLS handshake).
    // ---------------------------------------------------------------
    public static TrustManagerFactory buildTrustManagerFactory(String rootCaPem) throws Exception {
        X509Certificate rootCa = parseCertificate(rootCaPem);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("root-ca", rootCa);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }
}