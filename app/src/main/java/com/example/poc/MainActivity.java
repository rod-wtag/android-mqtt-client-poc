package com.example.poc;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class MainActivity extends AppCompatActivity {

    private Mqtt5AsyncClient client;
    private TextView statusText;
    private Button connectBtn;
    private EditText editMessage;
    private RadioButton radioHiveMQ;
    private boolean isConnected = false;

    private RecyclerView messageRecyclerView;
    private MessageAdapter messageAdapter;
    private List<Message> messageList;

    private MqttAsyncClient pahoClient;
    private boolean isPaho = false; // To track which client is active

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        connectBtn = findViewById(R.id.connectBtn);
        editMessage = findViewById(R.id.editMessage);
        radioHiveMQ = findViewById(R.id.radioHiveMQ);
        Button sendBtn = findViewById(R.id.sendBtn);
        messageRecyclerView = findViewById(R.id.messageRecyclerView);

        messageList = new ArrayList<>();
        messageAdapter = new MessageAdapter(messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messageRecyclerView.setLayoutManager(layoutManager);
        messageRecyclerView.setAdapter(messageAdapter);

        connectBtn.setOnClickListener(v -> {
            if (!isConnected) {
                if (radioHiveMQ.isChecked()) {
                    isPaho = false;
                    startMqttConnection();
                } else {
                    isPaho = true;
                    startPahoConnection();
                }
            } else {
                disconnectMqtt();
            }
        });

        sendBtn.setOnClickListener(v -> sendMessage());

        editMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void sendMessage() {
        String msg = editMessage.getText().toString().trim();
        if (msg.isEmpty()) return;

        if (isPaho && pahoClient != null && pahoClient.isConnected()) {
            publishPaho(msg);
            addMessageToList(new Message(msg, true));
            editMessage.setText("");
        } else if (!isPaho && client != null) {
            publishMessage(msg);
            addMessageToList(new Message(msg, true));
            editMessage.setText("");
        }
    }

    private void addMessageToList(Message message) {
        runOnUiThread(() -> {
            messageList.add(message);
            messageAdapter.notifyItemInserted(messageList.size() - 1);
            messageRecyclerView.smoothScrollToPosition(messageList.size() - 1);
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
            updateButtonState(true);

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
                                connectBtn.setBackgroundColor(android.graphics.Color.RED);
                            });
                            subscribeToTopic();
                        }
                    });
        } else {
            updateUI("Failed to get ssl config", "Getting config failed");
        }
    }

    private void disconnectMqtt() {
        if (client != null) {
            client.disconnect().whenComplete((v, throwable) -> {
                isConnected = false;
                runOnUiThread(() -> {
                    updateUI("Status: Disconnected", "Closed connection.");
                    connectBtn.setText("Connect to EMQX");
                    connectBtn.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"));
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
                .noLocal(true)
                .callback(publish -> {
                    String message = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
                    addMessageToList(new Message(message, false));
                })
                .send();
    }

    private void updateUI(String status, String logLine) {
        runOnUiThread(() -> {
            if (status != null) statusText.setText(status);
            if (logLine != null) {
                addMessageToList(new Message("System: " + logLine, false));
            }
        });
    }

    private MqttClientSslConfig getMtlsConfig() {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caIn = getResources().openRawResource(R.raw.ca);
            java.security.cert.Certificate ca = cf.generateCertificate(caIn);
            caIn.close();

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            InputStream clientIn = getResources().openRawResource(R.raw.client);

            char[] password = "123456".toCharArray();
            keyStore.load(clientIn, password);
            clientIn.close();

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            return MqttClientSslConfig.builder()
                    .keyManagerFactory(kmf)
                    .trustManagerFactory(tmf)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();

        } catch (Exception e) {
            return null;
        }
    }

    private javax.net.ssl.SSLSocketFactory getPahoSslFactory() {
        try {
            // Reuse your existing logic for CA and Client Certs
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream caIn = getResources().openRawResource(R.raw.ca);
            java.security.cert.Certificate ca = cf.generateCertificate(caIn);
            caIn.close();

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            InputStream clientIn = getResources().openRawResource(R.raw.client);
            char[] password = "123456".toCharArray();
            keyStore.load(clientIn, password);
            clientIn.close();

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            // This is the specific Paho part:
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());

            return sslContext.getSocketFactory();
        } catch (Exception e) {
            android.util.Log.e("PAHO_SSL", "Error: " + e.getMessage());
            return null;
        }
    }

    private void startPahoConnection() {
        try {
            isPaho = true;
            updateButtonState(true);

            String serverUri = "ssl://10.0.2.2:8883";
            String clientId = "paho-client-" + System.currentTimeMillis();

            pahoClient = new MqttAsyncClient(serverUri, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setSocketFactory(getPahoSslFactory());
            options.setCleanSession(true);
            options.setHttpsHostnameVerificationEnabled(false); // Same as hostnameVerifier(true)

            pahoClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    isConnected = true;
                    runOnUiThread(() -> {
                        updateUI("Status: Paho Connected!", "Connected via mTLS");
                        connectBtn.setText("Disconnect");
                        connectBtn.setEnabled(true);
                        connectBtn.setBackgroundColor(android.graphics.Color.RED);
                    });
                    subscribePaho();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    runOnUiThread(() -> {
                        updateUI("Paho Failed: " + exception.getMessage(), null);
                        updateButtonState(false);
                    });
                }
            });
        } catch (MqttException e) {
            updateUI("Paho Error", e.getMessage());
        }
    }

    private void publishPaho(String payload) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            pahoClient.publish("test/android", message);
        } catch (MqttException e) {
            updateUI(null, "Paho Send Failed: " + e.getMessage());
        }
    }

    private void subscribePaho() {
        try {
            pahoClient.subscribe("test/android", 0, (topic, message) -> {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                addMessageToList(new Message(payload, false));
            });
        } catch (MqttException e) {
            updateUI(null, "Paho Sub Error");
        }
    }
}
