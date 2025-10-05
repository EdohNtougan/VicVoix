package com.example.vicvoix;

import android.Manifest; import android.content.ContentValues; import android.content.Intent; import android.content.pm.PackageManager; import android.media.MediaPlayer; import android.net.Uri; import android.os.Build; import android.os.Bundle; import android.provider.MediaStore; import android.text.Editable; import android.text.InputFilter; import android.text.TextWatcher; import android.util.Base64; import android.util.Log; import android.view.View; import android.widget.AdapterView; import android.widget.ArrayAdapter; import android.widget.Button; import android.widget.EditText; import android.widget.ImageButton; import android.widget.ProgressBar; import android.widget.SeekBar; import android.widget.Spinner; import android.widget.TextView; import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher; import androidx.activity.result.contract.ActivityResultContracts; import androidx.appcompat.app.AppCompatActivity; import androidx.core.app.ActivityCompat; import androidx.core.content.ContextCompat; import androidx.core.content.FileProvider;

import org.json.JSONArray; import org.json.JSONObject;

import java.io.File; import java.io.FileOutputStream; import java.io.IOException; import java.io.OutputStream; import java.util.ArrayList; import java.util.HashMap; import java.util.List; import java.util.Map;

import okhttp3.Call; import okhttp3.Callback; import okhttp3.MediaType; import okhttp3.OkHttpClient; import okhttp3.Request; import okhttp3.RequestBody; import okhttp3.Response;

public class MainActivity extends AppCompatActivity { private static final String TAG = "VicVoixTTS"; private static final int PERMISSION_REQUEST = 101; private static final int MAX_CHARS = 5000;

// Views
private EditText etText;
private TextView tvCharCounter;
private Spinner spinnerVoices, spinnerStyles;
private SeekBar seekRate, seekPitch;
private TextView tvRateValue, tvPitchValue;
private Button btnLoadVoices, btnGenerate;
private ImageButton btnPlay, btnDownload;
private ProgressBar progressBar;

// Player & networking
private MediaPlayer mediaPlayer;
private OkHttpClient client = new OkHttpClient();

// Data
private List<String> voicesList = new ArrayList<>();
private String selectedVoice = "fr-FR-Wavenet-D"; // default
private float speakingRate = 1.0f;
private float pitch = 0.0f;
private final String apiKey = BuildConfig.TTS_API_KEY; // injected from secrets/build config

// Save launcher + temporary bytes
private ActivityResultLauncher<Intent> saveFileLauncher;
private byte[] lastAudioBytes = null;

// Style mapping (UI label -> SSML token)
private final Map<String, String> styleMap = new HashMap<>();

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    initViews();
    initStyleMap();
    setupSaveLauncher();
    setupListeners();
    requestPermissionsIfNeeded();
}

private void initViews() {
    etText = findViewById(R.id.etText);
    tvCharCounter = findViewById(R.id.tvCharCounter);
    spinnerVoices = findViewById(R.id.spinnerVoices);
    spinnerStyles = findViewById(R.id.spinnerStyles);
    seekRate = findViewById(R.id.seekRate);
    seekPitch = findViewById(R.id.seekPitch);
    tvRateValue = findViewById(R.id.tvRateValue);
    tvPitchValue = findViewById(R.id.tvPitchValue);
    btnLoadVoices = findViewById(R.id.btnLoadVoices);
    btnGenerate = findViewById(R.id.btnGenerate);
    btnPlay = findViewById(R.id.btnPlay);
    btnDownload = findViewById(R.id.btnDownload);
    progressBar = findViewById(R.id.progressBar);

    // Defensive: some layouts may not yet include optional views
    if (etText != null) {
        etText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CHARS)});
    }
    if (tvCharCounter != null) {
        tvCharCounter.setText("0 / " + MAX_CHARS);
    }
}

private void initStyleMap() {
    // Map visible labels -> SSML token/style name (to be used in mstts:express-as)
    styleMap.put("Aucun", "");
    styleMap.put("Narration", "narration");
    styleMap.put("Reportage", "newscast");
    styleMap.put("Documentaire", "documentary");
    styleMap.put("Podcast", "podcast");
    styleMap.put("Publicité", "advertisement");
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
                        }
                    }
                } else {
                    // L'utilisateur a annulé
                    resetGenerateButton();
                }
            }
    );
}

private void setupListeners() {
    // Rate
    if (seekRate != null && tvRateValue != null) {
        seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speakingRate = 0.25f + (progress / 375f) * 3.75f; // 0.25 to 4.0
                tvRateValue.setText(String.format("%.2f", speakingRate));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // Pitch
    if (seekPitch != null && tvPitchValue != null) {
        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pitch = -20.0f + (progress / 20.0f); // -20 to +20
                tvPitchValue.setText(String.format("%.1f", pitch));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

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

    // Spinner Styles - populate if present
    if (spinnerStyles != null) {
        List<String> labels = new ArrayList<>(styleMap.keySet());
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStyles.setAdapter(styleAdapter);
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

    // Optional Play and Download image buttons
    if (btnPlay != null) {
        btnPlay.setOnClickListener(v -> {
            String text = etText != null ? etText.getText().toString().trim() : "";
            if (text.isEmpty()) { Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show(); return; }
            if (voicesList.isEmpty()) { Toast.makeText(this, "Chargez les voix d'abord !", Toast.LENGTH_SHORT).show(); return; }
            generateTTS(text, false);
        });
    }

    if (btnDownload != null) {
        btnDownload.setOnClickListener(v -> {
            String text = etText != null ? etText.getText().toString().trim() : "";
            if (text.isEmpty()) { Toast.makeText(this, "Entrez du texte !", Toast.LENGTH_SHORT).show(); return; }
            if (voicesList.isEmpty()) { Toast.makeText(this, "Chargez les voix d'abord !", Toast.LENGTH_SHORT).show(); return; }
            generateTTS(text, true);
        });
    }
}

private void requestPermissionsIfNeeded() {
    // Only request WRITE_EXTERNAL_STORAGE for devices with SDK <= 28 (legacy)
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

/**
 * Génère la voix via l'API Google Text-to-Speech.
 * @param text Le texte à générer
 * @param wantDownload true = l'utilisateur a demandé le téléchargement; false = lecture directe
 */
private void generateTTS(String text, boolean wantDownload) {
    setUiBusy(true);
    String url = "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey;
    try {
        // Construire le JSON en fonction du style (ssml si style demandé)
        String selectedStyleLabel = (spinnerStyles != null && spinnerStyles.getSelectedItem() != null)
                ? spinnerStyles.getSelectedItem().toString() : "Aucun";
        String styleToken = styleMap.getOrDefault(selectedStyleLabel, "");

        JSONObject input = new JSONObject();
        if (!styleToken.isEmpty()) {
            // utiliser SSML
            String ssml = buildSsml(selectedVoice, styleToken, text);
            input.put("ssml", ssml);
        } else {
            input.put("text", text);
        }

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
                        // Lancer ACTION_CREATE_DOCUMENT pour sauvegarde via SAF
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("audio/mpeg");
                        intent.putExtra(Intent.EXTRA_TITLE, "VicVoix_" + System.currentTimeMillis() + ".mp3");
                        saveFileLauncher.launch(intent);
                    } else {
                        // Jouer directement depuis bytes
                        runOnUiThread(() -> {
                            playFromBytes(audioBytes);
                            resetGenerateButton();
                            setUiBusy(false);
                        });
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

private String buildSsml(String voiceName, String styleToken, String rawText) {
    // Basic SSML wrapper using mstts:express-as. Some voices may ignore the style if unsupported.
    String escaped = escapeForSsml(rawText);
    StringBuilder sb = new StringBuilder();
    sb.append("<speak>");
    sb.append("<voice name=\"").append(voiceName).append("\">");
    sb.append("<mstts:express-as style=\"").append(styleToken).append("\">");
    sb.append(escaped);
    sb.append("</mstts:express-as>");
    sb.append("</voice>");
    sb.append("</speak>");
    return sb.toString();
}

private String escapeForSsml(String raw) {
    if (raw == null) return "";
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
}

private void playFromBytes(byte[] audioBytes) {
    try {
        // write to cache
        File temp = new File(getCacheDir(), "vicvoix_temp.mp3");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(audioBytes);
        }

        // release previous
        releaseMediaPlayer();
        mediaPlayer = new MediaPlayer();
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", temp);
        mediaPlayer.setDataSource(this, uri);
        mediaPlayer.setOnPreparedListener(MediaPlayer::start);
        mediaPlayer.setOnCompletionListener(mp -> {
            releaseMediaPlayer();
        });
        mediaPlayer.prepareAsync();
    } catch (IOException e) {
        Toast.makeText(this, "Erreur lecture: " + e.getMessage(), Toast.LENGTH_LONG).show();
        Log.e(TAG, "playFromBytes", e);
    }
}

private void resetLoadButton() {
    runOnUiThread(() -> {
        if (btnLoadVoices != null) {
            btnLoadVoices.setEnabled(true);
            btnLoadVoices.setText("Charger les Voix Disponibles");
        }
    });
}

private void resetGenerateButton() {
    runOnUiThread(() -> {
        if (btnGenerate != null) {
            btnGenerate.setEnabled(true);
            btnGenerate.setText("Générer & Jouer Voix");
        }
    });
}

private void releaseMediaPlayer() {
    if (mediaPlayer != null) {
        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
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
    });
}

@Override
protected void onDestroy() {
    super.onDestroy();
    releaseMediaPlayer();
    client.dispatcher().executorService().shutdown();
}

// Optional: handle permission result for legacy devices
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

