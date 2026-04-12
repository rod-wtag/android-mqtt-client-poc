package com.example.poc;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class MainActivity extends AppCompatActivity {

    private Mqtt5AsyncClient client;
    private TextView statusText, messageLog;
    private Button connectBtn;
    private android.widget.EditText editMessage;
    private Button sendBtn;
    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        messageLog = findViewById(R.id.messageLog);
        connectBtn = findViewById(R.id.connectBtn);
        editMessage = findViewById(R.id.editMessage);
        sendBtn = findViewById(R.id.sendBtn);

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) {
                startMqttConnection();
                connectBtn.setBackgroundColor(android.graphics.Color.RED);
            } else {
                disconnectMqtt();
                connectBtn.setBackgroundColor(android.graphics.Color.parseColor("#6200EE")); // Default Purple
            }
        });

        sendBtn.setOnClickListener(v -> {
            String msg = editMessage.getText().toString();
            if (!msg.isEmpty() && client != null) {
                publishMessage(msg);
                editMessage.setText(""); // Clear input after sending
            }
        });

    }

    private void publishMessage(String payload) {
        client.publishWith()
                .topic("test/android")
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send()
                .whenComplete((publishResult, throwable) -> {
                    if (throwable != null) {
                        updateUI(null, "Send Failed: " + throwable.getMessage());
                    }
                });
    }

    private void startMqttConnection() {
        MqttClientSslConfig sslConfig = getMtlsConfig();
        if (sslConfig != null) {
            updateButtonState(true); // Disable while connecting to prevent double-clicks

            client = Mqtt5Client.builder()
                    .identifier("android-client-" + System.currentTimeMillis())
                    .serverHost("10.0.2.2")
                    .serverPort(8883)
                    .sslConfig(sslConfig)
                    .addDisconnectedListener(context -> {
                        isConnected = false;
                        runOnUiThread(() -> {
                            connectBtn.setText("Connect to EMQX");
                            updateUI("Status: Lost Connection", "Broker connection dropped.");
                        });
                    })
                    .buildAsync();

            client.connectWith()
                    .send()
                    .whenComplete((connAck, throwable) -> {
                        if (throwable != null) {
                            runOnUiThread(() -> {
                                updateUI("Failed: " + throwable.getMessage(), null);
                                updateButtonState(false);
                            });
                        } else {
                            isConnected = true;
                            runOnUiThread(() -> {
                                updateUI("Status: Connected!", "Connected to EMQX");
                                connectBtn.setText("Disconnect");
                                connectBtn.setEnabled(true);
                            });
                            subscribeToTopic();
                        }
                    });
        } else {
            updateUI("Failed to get ssl config", "Getting confirg failed");
        }
    }

    private void disconnectMqtt() {
        if (client != null) {
            client.disconnect().whenComplete((v, throwable) -> {
                isConnected = false;
                runOnUiThread(() -> {
                    updateUI("Status: Disconnected", "Closed connection.");
                    connectBtn.setText("Connect to EMQX");
                    updateButtonState(false);
                });
            });
        }
    }

    private void updateButtonState(boolean isProcessing) {
        runOnUiThread(() -> {
            if (isProcessing) {
                connectBtn.setEnabled(false);
                connectBtn.setText("Connecting...");
            } else {
                connectBtn.setEnabled(true);
            }
        });
    }

    private void subscribeToTopic() {
        client.subscribeWith()
                .topicFilter("test/android")
                .callback(publish -> {
                    String message = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
                    updateUI(null, "Msg: " + message);
                })
                .send();
    }

    private void updateUI(String status, String logLine) {
        runOnUiThread(() -> {
            if (status != null) statusText.setText(status);
            if (logLine != null) messageLog.append("\n" + logLine);
        });
    }

    private MqttClientSslConfig getMtlsConfig() {
        try {
            // 1. TRUST THE SERVER (Using the new ca.pem)
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caIn = getResources().openRawResource(R.raw.ca);
            java.security.cert.Certificate ca = cf.generateCertificate(caIn);
            caIn.close();

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // 2. IDENTIFY ANDROID (Using the new client.p12)
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            InputStream clientIn = getResources().openRawResource(R.raw.client);

            // Use the password we set: 123456
            char[] password = "123456".toCharArray();
            keyStore.load(clientIn, password);
            clientIn.close();

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            // 3. BUILD THE CONFIG
            return MqttClientSslConfig.builder()
                    .keyManagerFactory(kmf)
                    .trustManagerFactory(tmf)
                    // This bypasses the hostname check since we are using 10.0.2.2
                    .hostnameVerifier((hostname, session) -> true)
                    .build();

        } catch (Exception e) {
            android.util.Log.e("MQTT_SSL", "Error: " + e.getMessage());
            return null;
        }
    }
}