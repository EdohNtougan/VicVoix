package com.example.vicvoix;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "VicVoixTTS";
    private static final int PERMISSION_REQUEST = 1;

    private EditText etText;
    private Spinner spinnerVoices;
    private SeekBar seekRate, seekPitch;
    private TextView tvRateValue, tvPitchValue;
    private Button btnLoadVoices, btnGenerate;
    private MediaPlayer mediaPlayer;

    private OkHttpClient client = new OkHttpClient();
    private List<String> voicesList = new ArrayList<>();
    private String selectedVoice = "fr-FR-Wavenet-D";  // Default
    private float speakingRate = 1.0f;
    private float pitch = 0.0f;

    private String apiKey;  // Sera set en onCreate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);  // Fixed: Use main.xml

        // Set API key: Priorité BuildConfig, fallback hardcoded pour tests (remplace par ta vraie)
        apiKey = BuildConfig.TTS_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = "AIzaSyD4A5zGVfVxD8VKsPAb7NLiavHW8PhPwHo";  // Colle ta vraie clé du screenshot ici pour test
            Log.w(TAG, "BuildConfig.TTS_API_KEY vide - utilisation fallback. Configurez gradle pour prod.");
        }
        Log.d(TAG, "API Key length: " + apiKey.length());  // Debug: Vérifie si non vide

        initViews();
        setupListeners();
    }

    private void initViews() {
        etText = findViewById(R.id.etText);
        spinnerVoices = findViewById(R.id.spinnerVoices);
        seekRate = findViewById(R.id.seekRate);
        seekPitch = findViewById(R.id.seekPitch);
        tvRateValue = findViewById(R.id.tvRateValue);
        tvPitchValue = findViewById(R.id.tvPitchValue);
        btnLoadVoices = findViewById(R.id.btnLoadVoices);
        btnGenerate = findViewById(R.id.btnGenerate);

        // Disable generate si pas de permission initiale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            btnGenerate.setEnabled(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        }
    }

    private void setupListeners() {
        seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speakingRate = 0.25f + (progress / 375f) * 3.75f;  // 0.25 to 4.0
                tvRateValue.setText(String.format("%.2f", speakingRate));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pitch = -20.0f + (progress / 20.0f);  // -20 to +20
                tvPitchValue.setText(String.format("%.1f", pitch));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spinnerVoices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedVoice = voicesList.get(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnLoadVoices.setOnClickListener(v -> loadVoices());

        btnGenerate.setOnClickListener(v -> {
            String text = etText.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show();
                return;
            }
            if (voicesList.isEmpty()) {
                Toast.makeText(this, "Chargez les voix d'abord !", Toast.LENGTH_SHORT).show();
                return;
            }
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Clé API vide - vérifiez BuildConfig ou fallback !", Toast.LENGTH_LONG).show();
                return;
            }
            generateTTS(text);
        });
    }

    private void loadVoices() {
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Clé API vide - impossible de charger !", Toast.LENGTH_SHORT).show();
            return;
        }
        btnLoadVoices.setEnabled(false);
        btnLoadVoices.setText("Chargement...");
        String url = "https://texttospeech.googleapis.com/v1/voices?languageCode=fr-FR&key=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Erreur réseau pour voix: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Load voices failure", e);
                    resetLoadButton();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur API voix: " + response.code() + " - " + response.message(), Toast.LENGTH_LONG).show());
                    Log.e(TAG, "Load voices HTTP " + response.code());
                    resetLoadButton();
                    return;
                }
                try {
                    String body = response.body().string();
                    Log.d(TAG, "Réponse load voices: " + body.substring(0, Math.min(500, body.length())));  // Debug partial JSON
                    JSONObject json = new JSONObject(body);
                    JSONArray voices = json.getJSONArray("voices");
                    voicesList.clear();
                    for (int i = 0; i < voices.length(); i++) {
                        JSONObject voice = voices.getJSONObject(i);
                        String name = voice.getString("name");
                        if (name.startsWith("fr-FR-")) {
                            voicesList.add(name);
                        }
                    }
                    runOnUiThread(() -> {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_item, voicesList);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        spinnerVoices.setAdapter(adapter);
                        spinnerVoices.setSelection(0);
                        btnGenerate.setEnabled(true);
                        Toast.makeText(MainActivity.this, voicesList.size() + " voix fr-FR chargées !", Toast.LENGTH_SHORT).show();
                        resetLoadButton();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur parsing voix: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    Log.e(TAG, "Parse voices error", e);
                    resetLoadButton();
                }
            }
        });
    }

    private void generateTTS(String text) {
        // Check et request permission si besoin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            return;  // Relancera via onRequestPermissionsResult
        }

        btnGenerate.setEnabled(false);
        btnGenerate.setText("Génération...");
        String url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey;
        try {
            JSONObject input = new JSONObject().put("text", text.replace("\"", "\\\""));  // Échappement pour guillemets
            JSONObject voice = new JSONObject().put("languageCode", "fr-FR").put("name", selectedVoice);
            JSONObject audioConfig = new JSONObject()
                .put("audioEncoding", "MP3")
                .put("speakingRate", speakingRate)
                .put("pitch", pitch);
            JSONObject json = new JSONObject()
                .put("input", input)
                .put("voice", voice)
                .put("audioConfig", audioConfig);
            String jsonBody = json.toString();
            Log.d(TAG, "JSON body envoyé: " + jsonBody);  // Debug

            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Erreur réseau: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Generate TTS failure", e);
                        resetGenerateButton();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur API: " + response.code() + " - " + response.message(), Toast.LENGTH_LONG).show());
                        Log.e(TAG, "Generate TTS HTTP " + response.code());
                        resetGenerateButton();
                        return;
                    }
                    try {
                        String bodyStr = response.body().string();
                        Log.d(TAG, "Réponse generate: audioContent length=" + (bodyStr.contains("audioContent") ? bodyStr.split("\"audioContent\":\"")[1].split("\"")[0].length() : 0));  // Debug size
                        JSONObject jsonResponse = new JSONObject(bodyStr);
                        String audioContent = jsonResponse.getString("audioContent");
                        byte[] audioBytes = Base64.decode(audioContent, Base64.DEFAULT);
                        Log.d(TAG, "Audio bytes décodés: " + audioBytes.length);
                        saveAndPlayAudio(audioBytes, text);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur réponse/parse: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        Log.e(TAG, "Response parse error", e);
                        resetGenerateButton();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Erreur JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "JSON build error", e);
            resetGenerateButton();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission accordée - relancez Génération !", Toast.LENGTH_SHORT).show();
                btnGenerate.setEnabled(true);
            } else {
                Toast.makeText(this, "Permission refusée - impossible de sauvegarder audio.", Toast.LENGTH_LONG).show();
                btnGenerate.setEnabled(false);
            }
        }
    }

    private void saveAndPlayAudio(byte[] audioBytes, String text) {
        new Thread(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, "VicVoix_" + System.currentTimeMillis() + ".mp3");
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                Log.d(TAG, "Uri insertée: " + uri);
                if (uri != null) {
                    try (FileOutputStream fos = new FileOutputStream(getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor())) {
                        fos.write(audioBytes);
                    }
                    values.clear();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "MP3 sauvegardé dans Downloads !", Toast.LENGTH_LONG).show();
                        playAudio(uri);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "Erreur uri insert", Toast.LENGTH_SHORT).show());
                    resetGenerateButton();
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur save/play", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Erreur save/play: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    resetGenerateButton();
                });
            }
        }).start();
    }

    private void playAudio(Uri uri) {
        releaseMediaPlayer();
        mediaPlayer = MediaPlayer.create(this, uri);
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(mp -> {
                releaseMediaPlayer();
                resetGenerateButton();
            });
            mediaPlayer.start();
            Log.d(TAG, "Playback started");
        } else {
            Toast.makeText(this, "Erreur lecture - vérifiez fichier", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "MediaPlayer create failed");
            resetGenerateButton();
        }
    }

    private void resetLoadButton() {
        runOnUiThread(() -> {
            btnLoadVoices.setEnabled(true);
            btnLoadVoices.setText("Charger les Voix Disponibles");
        });
    }

    private void resetGenerateButton() {
        runOnUiThread(() -> {
            btnGenerate.setEnabled(true);
            btnGenerate.setText("Générer & Jouer Voix");
        });
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        client.dispatcher().executorService().shutdown();
    }
}
