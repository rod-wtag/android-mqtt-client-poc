package com.example.poc;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

public class CryptoManager {

    private static final String KEY_ALIAS = "afe-client-key";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    public static KeyPair getOrCreateHardwareKeyPair(Context context) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
            java.security.cert.Certificate cert = keyStore.getCertificate(KEY_ALIAS);
            if (privateKey != null && cert != null) {
                return new KeyPair(cert.getPublicKey(), privateKey);
            }
        }

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);

        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                builder.setIsStrongBoxBacked(true);
            }
        }

        keyPairGenerator.initialize(builder.build());
        return keyPairGenerator.generateKeyPair();
    }

    public static String generateCSR(KeyPair keyPair, String deviceId) throws Exception {
        X500Name entityName = new X500Name("CN=" + deviceId + ", O=YourCompany, C=US");

        JcaPKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(
                entityName, keyPair.getPublic());

        ContentSigner signer = new AndroidKeyStoreContentSigner(keyPair.getPrivate());
        PKCS10CertificationRequest p10Csr = csrBuilder.build(signer);

        String base64Csr = Base64.encodeToString(p10Csr.getEncoded(), Base64.NO_WRAP);

        return "-----BEGIN CERTIFICATE REQUEST-----\n" + base64Csr + "\n-----END CERTIFICATE REQUEST-----";
    }

    private static class AndroidKeyStoreContentSigner implements ContentSigner {
        private final PrivateKey privateKey;
        private final Signature signature;
        private final java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();

        public AndroidKeyStoreContentSigner(PrivateKey privateKey) throws Exception {
            this.privateKey = privateKey;
            this.signature = Signature.getInstance("SHA256withECDSA");
            this.signature.initSign(privateKey);
        }

        @Override
        public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
            return new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                    org.bouncycastle.asn1.x9.X9ObjectIdentifiers.ecdsa_with_SHA256);
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public byte[] getSignature() {
            try {
                signature.update(outputStream.toByteArray());
                return signature.sign();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
