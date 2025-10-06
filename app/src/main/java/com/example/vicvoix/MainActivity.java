// FICHIER MainActivity.java CORRIGÉ

package com.example.vicvoix;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "VicVoixTTS";
    private static final int PERMISSION_REQUEST = 101;
    private static final int MAX_CHARS = 5000;

    // Views
    private EditText etText;
    private TextView tvCharCounter;
    private Spinner spinnerVoices;
    // CORRECTION: Le spinner de style a été retiré car non supporté par l'API Google de cette manière.
    // private Spinner spinnerStyles;
    private SeekBar seekRate, seekPitch;
    private TextView tvRateValue, tvPitchValue;
    private Button btnLoadVoices, btnGenerate;
    private ImageButton btnPlay, btnDownload;
    private ProgressBar progressBar;

    // Player & networking
    private MediaPlayer mediaPlayer;
    private final OkHttpClient client = new OkHttpClient();

    // Data
    private List<String> voicesList = new ArrayList<>();
    private String selectedVoice = ""; // Initialisé vide
    private float speakingRate = 1.0f;
    private float pitch = 0.0f;
    private final String apiKey = BuildConfig.TTS_API_KEY;

    // Save launcher + temporary bytes
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private byte[] lastAudioBytes = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        initViews();
        setupSaveLauncher();
        setupListeners();
        requestPermissionsIfNeeded();
    }

    private void initViews() {
        etText = findViewById(R.id.etText);
        tvCharCounter = findViewById(R.id.tvCharCounter);
        spinnerVoices = findViewById(R.id.spinnerVoices);
        // spinnerStyles = findViewById(R.id.spinnerStyles); // Retiré
        seekRate = findViewById(R.id.seekRate);
        seekPitch = findViewById(R.id.seekPitch);
        tvRateValue = findViewById(R.id.tvRateValue);
        tvPitchValue = findViewById(R.id.tvPitchValue);
        btnLoadVoices = findViewById(R.id.btnLoadVoices);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnPlay = findViewById(R.id.btnPlay);
        btnDownload = findViewById(R.id.btnDownload);
        progressBar = findViewById(R.id.progressBar);

        if (etText != null) {
            etText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CHARS)});
        }
        if (tvCharCounter != null) {
            tvCharCounter.setText("0 / " + MAX_CHARS);
        }

        // CORRECTION: Initialisation des valeurs par défaut pour les sliders
        if (tvRateValue != null) tvRateValue.setText(String.format(Locale.US, "%.2f", speakingRate));
        if (tvPitchValue != null) tvPitchValue.setText(String.format(Locale.US, "%.1f", pitch));
    }

    // CORRECTION: La map de style et son initialisation sont retirées car non pertinentes.

    private void setupSaveLauncher() {
        saveFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null && lastAudioBytes != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                if (os != null) {
                                    os.write(lastAudioBytes);
                                    Toast.makeText(this, "MP3 sauvegardé", Toast.LENGTH_LONG).show();
                                }
                            } catch (IOException e) {
                                Toast.makeText(this, "Erreur écriture fichier: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                Log.e(TAG, "saveFileLauncher write", e);
                            }
                        }
                    }
                    // Quoi qu'il arrive, on libère l'état "occupé"
                    lastAudioBytes = null;
                    setUiBusy(false);
                }
        );
    }

    private void setupListeners() {
        // Rate
        if (seekRate != null) {
            seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // CORRECTION: Formule de mapping plus simple et précise
                    // La plage de l'API est [0.25, 4.0]. Votre seekBar a un max de 100 (par défaut).
                    // progress=0 -> 0.25, progress=25 -> 1.0, progress=100 -> 4.0
                    if (progress < 25) { // Ralentissement
                        speakingRate = 0.25f + (progress / 25.0f) * 0.75f;
                    } else { // Accélération
                        speakingRate = 1.0f + ((progress - 25) / 75.0f) * 3.0f;
                    }
                    if (tvRateValue != null) tvRateValue.setText(String.format(Locale.US, "%.2f", speakingRate));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Pitch
        if (seekPitch != null) {
            seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // CORRECTION: Mapping simple de [0, 100] vers [-20.0, 20.0]
                    // progress=0 -> -20.0, progress=50 -> 0.0, progress=100 -> 20.0
                    pitch = -20.0f + (progress / 100.0f) * 40.0f;
                    if (tvPitchValue != null) tvPitchValue.setText(String.format(Locale.US, "%.1f", pitch));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (etText != null) {
            etText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (tvCharCounter != null) {
                        tvCharCounter.setText(s.length() + " / " + MAX_CHARS);
                    }
                }
            });
        }

        if (spinnerVoices != null) {
            spinnerVoices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedVoice = (String) parent.getItemAtPosition(position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (btnLoadVoices != null) {
            btnLoadVoices.setOnClickListener(v -> loadVoices());
        }

        // CORRECTION: Un seul listener pour tous les boutons de génération pour éviter la duplication.
        View.OnClickListener generateListener = v -> {
            String text = etText != null ? etText.getText().toString().trim() : "";
            if (text.isEmpty()) {
                Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show();
                return;
            }
            if (voicesList.isEmpty() || selectedVoice.isEmpty()) {
                Toast.makeText(this, "Chargez et sélectionnez une voix !", Toast.LENGTH_SHORT).show();
                return;
            }
            // Déterminer si on télécharge ou on joue
            boolean wantDownload = (v.getId() == R.id.btnDownload);
            generateTTS(text, wantDownload);
        };

        if (btnGenerate != null) btnGenerate.setOnClickListener(generateListener);
        if (btnPlay != null) btnPlay.setOnClickListener(generateListener);
        if (btnDownload != null) btnDownload.setOnClickListener(generateListener);
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT <= 28) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            }
        }
    }

    private void loadVoices() {
        setUiBusy(true); // Bloquer l'UI pendant le chargement
        String url = "https://texttospeech.googleapis.com/v1/voices?languageCode=fr-FR&key=" + apiKey;
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Erreur réseau: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setUiBusy(false);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Erreur API voix: " + response.code(), Toast.LENGTH_LONG).show();
                        setUiBusy(false);
                    });
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray voices = json.getJSONArray("voices");
                    voicesList.clear();
                    for (int i = 0; i < voices.length(); i++) {
                        voicesList.add(voices.getJSONObject(i).getString("name"));
                    }
                    runOnUiThread(() -> {
                        if (spinnerVoices != null) {
                            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_item, voicesList);
                            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerVoices.setAdapter(adapter);
                        }
                        if (btnGenerate != null) btnGenerate.setEnabled(true);
                        Toast.makeText(MainActivity.this, voicesList.size() + " voix fr-FR chargées !", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Erreur parsing voix: " + e.getMessage(), Toast.LENGTH_LONG).show());
                } finally {
                    runOnUiThread(() -> setUiBusy(false));
                }
            }
        });
    }

    private void generateTTS(String text, boolean wantDownload) {
        setUiBusy(true);
        String url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey;
        try {
            // CORRECTION: Construction de la requête JSON avec SSML systématique
            JSONObject json = new JSONObject();

            // 1. INPUT: Toujours utiliser SSML pour inclure les paramètres
            String ssml = buildSsml(text, speakingRate, pitch);
            json.put("input", new JSONObject().put("ssml", ssml));

            // 2. VOICE: Inchangé
            json.put("voice", new JSONObject().put("languageCode", "fr-FR").put("name", selectedVoice));

            // 3. AUDIO CONFIG: On ne met QUE l'encodage. Vitesse et hauteur sont dans le SSML.
            json.put("audioConfig", new JSONObject().put("audioEncoding", "MP3"));

            RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Erreur réseau: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        setUiBusy(false);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "Aucun détail";
                        Log.e(TAG, "API Error: " + response.code() + " - " + errorBody);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Erreur API (" + response.code() + ")", Toast.LENGTH_LONG).show();
                            setUiBusy(false);
                        });
                        return;
                    }
                    try {
                        JSONObject jsonResponse = new JSONObject(response.body().string());
                        byte[] audioBytes = Base64.decode(jsonResponse.getString("audioContent"), Base64.DEFAULT);

                        if (wantDownload) {
                            lastAudioBytes = audioBytes;
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("audio/mpeg");
                            intent.putExtra(Intent.EXTRA_TITLE, "VicVoix_" + System.currentTimeMillis() + ".mp3");
                            runOnUiThread(() -> saveFileLauncher.launch(intent));
                        } else {
                            runOnUiThread(() -> {
                                playFromBytes(audioBytes);
                                setUiBusy(false);
                            });
                        }

                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Erreur réponse: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            setUiBusy(false);
                        });
                    }
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "Erreur construction JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setUiBusy(false);
        }
    }

    /**
     * CORRECTION: Nouvelle fonction pour construire le SSML correctement.
     * Cette fonction prend le texte, la vitesse et la hauteur et les assemble.
     */
    private String buildSsml(String text, float rate, float pitch) {
        String escapedText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");

        return String.format(Locale.US,
                "<speak><prosody rate='%.2f' pitch='%.2fdB'>%s</prosody></speak>",
                rate,
                pitch,
                escapedText
        );
    }

    private void playFromBytes(byte[] audioBytes) {
        try {
            File tempMp3 = new File(getCacheDir(), "vicvoix_temp.mp3");
            try (FileOutputStream fos = new FileOutputStream(tempMp3)) {
                fos.write(audioBytes);
            }

            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            // Utiliser un FileDescriptor est plus robuste que le FileProvider pour le cache interne.
            mediaPlayer.setDataSource(new FileOutputStream(tempMp3).getFD());
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnCompletionListener(mp -> releaseMediaPlayer());
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Toast.makeText(this, "Erreur lecture: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "playFromBytes", e);
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void setUiBusy(boolean busy) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (btnGenerate != null) btnGenerate.setEnabled(!busy);
            if (btnLoadVoices != null) btnLoadVoices.setEnabled(!busy);
            if (btnPlay != null) btnPlay.setEnabled(!busy);
            if (btnDownload != null) btnDownload.setEnabled(!busy);
            // On désactive aussi les réglages pendant la génération
            if (etText != null) etText.setEnabled(!busy);
            if (spinnerVoices != null) spinnerVoices.setEnabled(!busy);
            if (seekRate != null) seekRate.setEnabled(!busy);
            if (seekPitch != null) seekPitch.setEnabled(!busy);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        // Libérer proprement les ressources de OkHttp
        client.dispatcher().cancelAll();
    }
    
    // Inchangé
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission stockage refusée.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
