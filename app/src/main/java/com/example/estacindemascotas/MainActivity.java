
package com.example.estacindemascotas;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String MQTT_BROKER = "ssl://9c5f5ba94eae45e7978cc0e2c0d4f783.s1.eu.hivemq.cloud:8883";
    private static final String MQTT_CLIENT_ID = "android_client";
    private static final String MQTT_DATA_TOPIC = "mascotas/datos";
    private static final String MQTT_STATUS_TOPIC = "mascotas/status";
    private static final String MQTT_CONTROL_TOPIC = "mascotas/control";
    private static final String MQTT_USERNAME = "cristobal";
    private static final String MQTT_PASSWORD = "Cristobal_2016";

    private MqttClient mqttClient;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    private TextView textViewStatus;
    private EditText editTextFoodAmount, editTextFoodLevel, editTextWaterLevel, editTextFeedingTime1, editTextFeedingTime2;
    private Button buttonDispense, buttonUpdateFood, buttonResetFood, buttonUpdateWater, buttonResetWater, buttonSaveSchedule, buttonUpdateActivity, buttonRefillAll, buttonResetSystem;
    private Spinner spinnerActivityLevel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();

        initializeViews();
        setupClickListeners();

        signInAnonymously();
        new Thread(this::connectMqtt).start();
    }

    private void initializeViews() {
        textViewStatus = findViewById(R.id.textViewStatus);
        editTextFoodAmount = findViewById(R.id.editTextFoodAmount);
        editTextFoodLevel = findViewById(R.id.editTextFoodLevel);
        editTextWaterLevel = findViewById(R.id.editTextWaterLevel);
        editTextFeedingTime1 = findViewById(R.id.editTextFeedingTime1);
        editTextFeedingTime2 = findViewById(R.id.editTextFeedingTime2);
        spinnerActivityLevel = findViewById(R.id.spinnerActivityLevel);

        buttonDispense = findViewById(R.id.buttonDispense);
        buttonUpdateFood = findViewById(R.id.buttonUpdateFood);
        buttonResetFood = findViewById(R.id.buttonResetFood);
        buttonUpdateWater = findViewById(R.id.buttonUpdateWater);
        buttonResetWater = findViewById(R.id.buttonResetWater);
        buttonSaveSchedule = findViewById(R.id.buttonSaveSchedule);
        buttonUpdateActivity = findViewById(R.id.buttonUpdateActivity);
        buttonRefillAll = findViewById(R.id.buttonRefillAll);
        buttonResetSystem = findViewById(R.id.buttonResetSystem);
    }

    private void setupClickListeners() {
        buttonDispense.setOnClickListener(v -> {
            String amountStr = editTextFoodAmount.getText().toString();
            if (!amountStr.isEmpty()) {
                publishControlMessage("dispense", "amount", Integer.parseInt(amountStr));
            }
        });

        buttonUpdateFood.setOnClickListener(v -> {
            String levelStr = editTextFoodLevel.getText().toString();
            if (!levelStr.isEmpty()) {
                publishControlMessage("update_food_level", "level", Integer.parseInt(levelStr));
            }
        });

        buttonResetFood.setOnClickListener(v -> publishControlMessage("reset_food_level", null, null));

        buttonUpdateWater.setOnClickListener(v -> {
            String levelStr = editTextWaterLevel.getText().toString();
            if (!levelStr.isEmpty()) {
                publishControlMessage("update_water_level", "level", Integer.parseInt(levelStr));
            }
        });

        buttonResetWater.setOnClickListener(v -> publishControlMessage("reset_water_level", null, null));

        buttonSaveSchedule.setOnClickListener(v -> {
            String time1 = editTextFeedingTime1.getText().toString();
            String time2 = editTextFeedingTime2.getText().toString();
            try {
                JSONObject payload = new JSONObject();
                payload.put("time1", time1);
                payload.put("time2", time2);
                publishControlMessage("set_schedule", payload);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating schedule JSON", e);
            }
        });

        buttonUpdateActivity.setOnClickListener(v -> {
            String activityLevel = spinnerActivityLevel.getSelectedItem().toString();
            publishControlMessage("update_activity", "level", activityLevel);
        });

        buttonRefillAll.setOnClickListener(v -> publishControlMessage("refill_all", null, null));
        buttonResetSystem.setOnClickListener(v -> publishControlMessage("reset_system", null, null));
    }

    private void signInAnonymously() {
        mAuth.signInAnonymously()
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "signInAnonymously:success");
                    } else {
                        Log.w(TAG, "signInAnonymously:failure", task.getException());
                        Toast.makeText(MainActivity.this, "Authentication failed.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void connectMqtt() {
        try {
            mqttClient = new MqttClient(MQTT_BROKER, MQTT_CLIENT_ID, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setUserName(MQTT_USERNAME);
            connOpts.setPassword(MQTT_PASSWORD.toCharArray());
            connOpts.setCleanSession(true);
            connOpts.setWill(MQTT_STATUS_TOPIC, "offline".getBytes(), 0, false);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    Log.d(TAG, "Connection lost");
                    updateStatus(false);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    Log.d(TAG, "Message arrived: " + new String(message.getPayload()));
                    updatePetStationStateInFirestore(new String(message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            Log.d(TAG, "Connecting to broker: " + MQTT_BROKER);
            mqttClient.connect(connOpts);
            Log.d(TAG, "Connected");

            mqttClient.publish(MQTT_STATUS_TOPIC, new MqttMessage("online".getBytes()));
            Log.d(TAG, "Published online status");

            mqttClient.subscribe(MQTT_DATA_TOPIC, 0);
            Log.d(TAG, "Subscribed to topic: " + MQTT_DATA_TOPIC);

            updateStatus(true);

        } catch (MqttException e) {
            Log.e(TAG, "MQTT Connection Error", e);
            updateStatus(false);
        }
    }

    private void updatePetStationStateInFirestore(String message) {
        try {
            JSONObject messageJson = new JSONObject(message);
            Map<String, Object> dataMap = jsonToMap(messageJson);
            dataMap.put("last_update", System.currentTimeMillis());

            db.collection("pet_stations").document("main_station")
                    .set(dataMap, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Pet station state successfully updated!"))
                    .addOnFailureListener(e -> Log.w(TAG, "Error updating pet station state", e));
        } catch (JSONException e) {
            Log.e(TAG, "Error processing JSON message for Firestore update", e);
        }
    }

    public static Map<String, Object> jsonToMap(JSONObject json) throws JSONException {
        Map<String, Object> retMap = new HashMap<>();
        if (json != JSONObject.NULL) {
            retMap = toMap(json);
        }
        return retMap;
    }

    public static Map<String, Object> toMap(JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keysItr = object.keys();
        while (keysItr.hasNext()) {
            String key = keysItr.next();
            Object value = object.get(key);
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    public static List<Object> toList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONArray) {
                value = toList((JSONArray) value);
            } else if (value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }


    private void publishControlMessage(String action, String key, Object value) {
        try {
            JSONObject payload = new JSONObject();
            if (key != null && value != null) {
                payload.put(key, value);
            }
            publishControlMessage(action, payload);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON for control message", e);
        }
    }

    private void publishControlMessage(String action, JSONObject payload) {
        new Thread(() -> {
            if (mqttClient == null || !mqttClient.isConnected()) {
                Log.e(TAG, "MQTT client not connected, cannot publish message.");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No se pudo enviar el comando. MQTT desconectado.", Toast.LENGTH_SHORT).show());
                return;
            }
            try {
                JSONObject messageJson = new JSONObject();
                messageJson.put("action", action);
                messageJson.put("payload", payload);

                MqttMessage message = new MqttMessage(messageJson.toString().getBytes());
                mqttClient.publish(MQTT_CONTROL_TOPIC, message);
                Log.d(TAG, "Published control message: " + messageJson.toString());
            } catch (MqttException | JSONException e) {
                Log.e(TAG, "Error publishing control message", e);
            }
        }).start();
    }

    private void updateStatus(final boolean isConnected) {
        runOnUiThread(() -> {
            if (isConnected) {
                textViewStatus.setText(R.string.status_connected);
                textViewStatus.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.light_green));
                textViewStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.dark_green));
            } else {
                textViewStatus.setText(R.string.status_disconnected);
                textViewStatus.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.light_red));
                textViewStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.dark_red));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        new Thread(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.publish(MQTT_STATUS_TOPIC, new MqttMessage("offline".getBytes()));
                    Log.d(TAG, "Published offline status");
                    mqttClient.disconnect();
                }
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
