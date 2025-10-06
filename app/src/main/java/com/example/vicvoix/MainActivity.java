package com.example.vicvoix;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
    private static final int MAX_CHARS = 3000;

    // Views
    private EditText etText;
    private TextView tvCharCounter;
    private Spinner spinnerVoices;
    private Button btnLoadVoices, btnGenerate;
    private ImageButton btnPlay, btnDownload;
    private ProgressBar progressBar;
    private SeekBar audioProgress;

    // Player & networking
    private MediaPlayer mediaPlayer;
    private OkHttpClient client = new OkHttpClient();
    private Handler handler = new Handler(Looper.getMainLooper());

    // Data
    private List<String> voicesList = new ArrayList<>();
    private String selectedVoice = "fr-FR-Wavenet-D"; // default
    private final String apiKey = BuildConfig.TTS_API_KEY; // injected from secrets/build config

    // Save launcher + temporary bytes
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private byte[] lastAudioBytes = null;

    // Runnable for updating SeekBar progress
    private Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                audioProgress.setProgress(mediaPlayer.getCurrentPosition());
                handler.postDelayed(this, 1000); // Update every second
            }
        }
    };

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
        btnLoadVoices = findViewById(R.id.btnLoadVoices);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnPlay = findViewById(R.id.btnPlay);
        btnDownload = findViewById(R.id.btnDownload);
        progressBar = findViewById(R.id.progressBar);
        audioProgress = findViewById(R.id.audioProgress);

        if (etText != null) {
            etText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CHARS)});
        }
        if (tvCharCounter != null) {
            tvCharCounter.setText("0 / " + MAX_CHARS);
        }
    }

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
                                    // Optionnel: jouer après sauvegarde
                                    playFromBytes(lastAudioBytes);
                                }
                            } catch (IOException e) {
                                Toast.makeText(this, "Erreur écriture fichier: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                Log.e(TAG, "saveFileLauncher write", e);
                            } finally {
                                lastAudioBytes = null;
                                resetGenerateButton();
                                setUiBusy(false); // Ajout pour cacher le spinner après save
                            }
                        }
                    } else {
                        // L'utilisateur a annulé
                        resetGenerateButton();
                        setUiBusy(false); // Ajout pour cacher le spinner si annulé
                    }
                }
        );
    }

    private void setupListeners() {
        // Text counter
        if (etText != null) {
            etText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (tvCharCounter != null) {
                        int len = s.length();
                        tvCharCounter.setText(len + " / " + MAX_CHARS);
                    }
                }
            });
        }

        // Spinner Voices selection
        if (spinnerVoices != null) {
            spinnerVoices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position >= 0 && position < voicesList.size()) {
                        selectedVoice = voicesList.get(position);
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // Buttons
        if (btnLoadVoices != null) {
            btnLoadVoices.setOnClickListener(v -> loadVoices());
        }

        if (btnGenerate != null) {
            btnGenerate.setOnClickListener(v -> {
                String text = etText != null ? etText.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (voicesList.isEmpty()) {
                    Toast.makeText(this, "Chargez les voix d'abord !", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Par défaut: génération + lecture
                generateTTS(text, false);
            });
        }

        // Play button: Toggle play/pause
        if (btnPlay != null) {
            btnPlay.setOnClickListener(v -> togglePlayPause());
        }

        // Download button
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> {
                String text = etText != null ? etText.getText().toString().trim() : "";
                if (text.isEmpty()) { Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show(); return; }
                if (voicesList.isEmpty()) { Toast.makeText(this, "Chargez les voix d'abord !", Toast.LENGTH_SHORT).show(); return; }
                generateTTS(text, true);
            });
        }

        // Audio Progress SeekBar
        if (audioProgress != null) {
            audioProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mediaPlayer != null) {
                        mediaPlayer.seekTo(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    handler.removeCallbacks(updateProgress);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        handler.post(updateProgress);
                    }
                }
            });
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Générez d'abord l'audio !", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            btnPlay.setImageResource(R.drawable.ic_play);
            handler.removeCallbacks(updateProgress); // Stop updating on pause
        } else {
            mediaPlayer.start();
            btnPlay.setImageResource(R.drawable.ic_pause);
            handler.post(updateProgress); // Start updating progress
        }
        setUiBusy(false); // Ajout pour cacher le spinner sur play/pause
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT <= 28) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            }
        }
    }

    private void loadVoices() {
        if (btnLoadVoices != null) {
            btnLoadVoices.setEnabled(false);
            btnLoadVoices.setText("Chargement...");
        }
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
                        if (name.startsWith("fr-FR-")) {
                            voicesList.add(name);
                        }
                    }
                    runOnUiThread(() -> {
                        if (spinnerVoices != null) {
                            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_spinner_item, voicesList);
                            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spinnerVoices.setAdapter(adapter);
                            spinnerVoices.setSelection(0);
                        }
                        if (btnGenerate != null) btnGenerate.setEnabled(true);
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

    private void generateTTS(String text, boolean wantDownload) {
        setUiBusy(true);
        String url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey;
        try {
            JSONObject input = new JSONObject();
            input.put("text", text);

            JSONObject voice = new JSONObject().put("languageCode", "fr-FR").put("name", selectedVoice);
            JSONObject audioConfig = new JSONObject()
                    .put("audioEncoding", "MP3")
                    .put("speakingRate", 1.0f)
                    .put("pitch", 0.0f);

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
                        setUiBusy(false);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Erreur API: " + response.code(), Toast.LENGTH_LONG).show();
                            resetGenerateButton();
                            setUiBusy(false);
                        });
                        return;
                    }
                    try {
                        String bodyStr = response.body().string();
                        JSONObject jsonResponse = new JSONObject(bodyStr);
                        String audioContent = jsonResponse.getString("audioContent");
                        byte[] audioBytes = Base64.decode(audioContent, Base64.DEFAULT);

                        if (wantDownload) {
                            lastAudioBytes = audioBytes;
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("audio/mpeg");
                            intent.putExtra(Intent.EXTRA_TITLE, "VicVoix_" + System.currentTimeMillis() + ".mp3");
                            saveFileLauncher.launch(intent);
                        } else {
                            runOnUiThread(() -> {
                                playFromBytes(audioBytes);
                                resetGenerateButton();
                                // Déplacé setUiBusy(false) après playFromBytes pour s'assurer qu'il disparaisse une fois l'audio prêt
                            });
                            setUiBusy(false); // Appel immédiat pour cacher pendant la préparation async
                        }

                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Erreur réponse: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            resetGenerateButton();
                            setUiBusy(false);
                        });
                    }
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "Erreur JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            resetGenerateButton();
            setUiBusy(false);
        }
    }

    private void playFromBytes(byte[] audioBytes) {
        try {
            // Write to cache
            File temp = new File(getCacheDir(), "vicvoix_temp.mp3");
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                fos.write(audioBytes);
            }

            // Release previous
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", temp);
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepareAsync(); // Async prepare

            mediaPlayer.setOnPreparedListener(mp -> {
                audioProgress.setMax(mp.getDuration());
                audioProgress.setProgress(0);
                mp.start();
                btnPlay.setImageResource(R.drawable.ic_pause);
                handler.post(updateProgress); // Start progress update
                setUiBusy(false); // Ajout pour cacher le spinner une fois prêt à jouer
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                btnPlay.setImageResource(R.drawable.ic_play);
                audioProgress.setProgress(0);
                handler.removeCallbacks(updateProgress);
                setUiBusy(false); // Ajout pour cacher à la fin
            });

        } catch (IOException e) {
            Toast.makeText(this, "Erreur lecture: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "playFromBytes", e);
            setUiBusy(false); // Ajout en cas d'erreur
        }
    }

    private void resetLoadButton() {
        runOnUiThread(() -> {
            if (btnLoadVoices != null) {
                btnLoadVoices.setEnabled(true);
                btnLoadVoices.setText("Voix Chargées");
            }
        });
    }

    private void resetGenerateButton() {
        runOnUiThread(() -> {
            if (btnGenerate != null) {
                btnGenerate.setEnabled(true);
                btnGenerate.setText("Générer");
            }
        });
    }

    private void releaseMediaPlayer() {
        handler.removeCallbacks(updateProgress);
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        btnPlay.setImageResource(R.drawable.ic_play);
        audioProgress.setProgress(0);
        setUiBusy(false); // Ajout pour cacher le spinner sur release
    }

    private void setUiBusy(boolean busy) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (btnGenerate != null) btnGenerate.setEnabled(!busy);
            if (btnLoadVoices != null) btnLoadVoices.setEnabled(!busy);
            if (btnPlay != null) btnPlay.setEnabled(!busy);
            if (btnDownload != null) btnDownload.setEnabled(!busy);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        client.dispatcher().executorService().shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission stockage refusée : certaines fonctions peuvent être limitées.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
