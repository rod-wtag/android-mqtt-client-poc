package com.example.poc;

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
                    startMqttConnection();
                } else {
                    updateUI("Paho client is not yet implemented", "Paho client is not implemented yet");
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
        if (!msg.isEmpty() && client != null) {
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
}
