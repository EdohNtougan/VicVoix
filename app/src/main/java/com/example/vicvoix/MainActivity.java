package com.example.vicvoix;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
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

    private final String apiKey = BuildConfig.TTS_API_KEY;  // From secret

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
    }

    private void setupListeners() {
        seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speakingRate = 0.25f + (progress / 1000f) * 3.75f;  // 0.25 to 4.0
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
            generateTTS(text);
        });
    }

    private void loadVoices() {
        btnLoadVoices.setEnabled(false);
        btnLoadVoices.setText("Chargement...");
        String url = "https://texttospeech.googleapis.com/v1/voices?languageCode=fr-FR&key=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Erreur réseau pour voix: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    resetLoadButton();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur API voix: " + response.code(), Toast.LENGTH_LONG).show());
                    resetLoadButton();
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray voices = json.getJSONArray("voices");
                    voicesList.clear();
                    for (int i = 0; i < voices.length(); i++) {
                        JSONObject voice = voices.getJSONObject(i);
                        String name = voice.getString("name");
                        if (name.startsWith("fr-FR-")) {  // Filtre fr-FR
                            voicesList.add(name);
                        }
                    }
                    runOnUiThread(() -> {
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_item, voicesList);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        spinnerVoices.setAdapter(adapter);
                        spinnerVoices.setSelection(0);  // Default
                        btnGenerate.setEnabled(true);
                        Toast.makeText(MainActivity.this, voicesList.size() + " voix fr-FR chargées !", Toast.LENGTH_SHORT).show();
                        resetLoadButton();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur parsing voix: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    resetLoadButton();
                }
            }
        });
    }

    private void generateTTS(String text) {
        btnGenerate.setEnabled(false);
        btnGenerate.setText("Génération...");
        String url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey;
        try {
            JSONObject input = new JSONObject().put("text", text);
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

            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Erreur réseau: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        resetGenerateButton();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur API: " + response.code(), Toast.LENGTH_LONG).show());
                        resetGenerateButton();
                        return;
                    }
                    try {
                        String bodyStr = response.body().string();
                        JSONObject jsonResponse = new JSONObject(bodyStr);
                        String audioContent = jsonResponse.getString("audioContent");
                        byte[] audioBytes = Base64.decode(audioContent, Base64.DEFAULT);
                        saveAndPlayAudio(audioBytes, text);
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur réponse: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        resetGenerateButton();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Erreur JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            resetGenerateButton();
        }
    }

    private void saveAndPlayAudio(byte[] audioBytes, String text) {
        new Thread(() -> {
            try {
                // Sauvegarde via MediaStore pour Downloads (scoped, pas de permission post-Android 10)
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, "VicVoix_" + System.currentTimeMillis() + ".mp3");
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
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
                    runOnUiThread(() -> Toast.makeText(this, "Erreur sauvegarde", Toast.LENGTH_SHORT).show());
                    resetGenerateButton();
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur save/play", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        } else {
            Toast.makeText(this, "Erreur lecture", Toast.LENGTH_SHORT).show();
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
