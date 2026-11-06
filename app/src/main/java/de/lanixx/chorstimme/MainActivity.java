package de.lanixx.chorstimme;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import de.lanixx.chorstimme.core.AbcParser;
import de.lanixx.chorstimme.core.AbcWriter;
import de.lanixx.chorstimme.core.ChoirSynth;
import de.lanixx.chorstimme.core.MidiWriter;
import de.lanixx.chorstimme.core.Mp3Encoder;
import de.lanixx.chorstimme.core.PdfReader;
import de.lanixx.chorstimme.core.PdfScoreExtractor;
import de.lanixx.chorstimme.core.Score;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen: choose a sheet-music PDF (abcjs) or ABC text, set tempo and voice emphasis,
 * render a practice MP3, listen to it, save MP3 and MIDI.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN_PDF = 1;
    private static final int REQ_OPEN_ABC = 2;
    private static final int REQ_SAVE_MP3 = 3;
    private static final int REQ_SAVE_MIDI = 4;
    private static final int REQ_SAVE_ABC = 5;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Score score;
    private String baseName = "Uebung";
    private byte[] mp3;
    private File mp3File;
    private MediaPlayer player;
    private boolean busy;

    private TextView infoView, warnView, lyricsView, tempoLabel, statusView, timeView;
    private SeekBar tempoBar, posBar;
    private Spinner emphasis;
    private CheckBox countIn;
    private Button generateBtn, playBtn, saveMp3Btn, saveMidiBtn, saveAbcBtn;
    private LinearLayout exportRow;
    private ProgressBar progress;
    private LinearLayout playerBox;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        mp3File = new File(getCacheDir(), "uebung.mp3");
        setContentView(buildUi());
        refresh();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
            playBtn.setText("▶  Abspielen");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(root);

        TextView hint = new TextView(this);
        hint.setText("Noten-PDF (aus abcjs gedruckt) oder ABC-Notation laden. Die App erzeugt daraus "
                + "eine Übungsaufnahme (Chor-Vokalise) als MP3 und eine MIDI-Datei.");
        hint.setTextSize(14);
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button openPdf = button("PDF öffnen");
        Button openAbc = button("ABC-Datei");
        row.addView(openPdf, weight());
        row.addView(openAbc, weight());
        root.addView(row, matchWrap(dp(12)));
        Button pasteAbc = button("ABC-Text einfügen");
        root.addView(pasteAbc, matchWrap(0));

        openPdf.setOnClickListener(v -> open(REQ_OPEN_PDF, "application/pdf"));
        openAbc.setOnClickListener(v -> open(REQ_OPEN_ABC, "*/*"));
        pasteAbc.setOnClickListener(v -> pasteDialog());

        infoView = new TextView(this);
        infoView.setTextSize(15);
        infoView.setTextIsSelectable(true);
        root.addView(infoView, matchWrap(dp(16)));

        warnView = new TextView(this);
        warnView.setTextColor(Color.rgb(0xB0, 0x30, 0x20));
        warnView.setTextSize(14);
        root.addView(warnView, matchWrap(dp(6)));

        tempoLabel = new TextView(this);
        tempoLabel.setTextSize(15);
        root.addView(tempoLabel, matchWrap(dp(16)));
        tempoBar = new SeekBar(this);
        tempoBar.setMax(20);          // 50 % .. 150 % in 5 % steps
        tempoBar.setProgress(10);
        tempoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { optionsChanged(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(tempoBar, matchWrap(0));

        TextView emLabel = new TextView(this);
        emLabel.setText("Stimme hervorheben");
        emLabel.setTextSize(15);
        root.addView(emLabel, matchWrap(dp(12)));
        emphasis = new Spinner(this);
        root.addView(emphasis, matchWrap(0));
        emphasis.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { optionsChanged(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        countIn = new CheckBox(this);
        countIn.setText("Einzähler (4 Klicks vor dem Einsatz)");
        countIn.setChecked(true);
        countIn.setOnCheckedChangeListener((b, c) -> optionsChanged());
        root.addView(countIn, matchWrap(dp(8)));

        generateBtn = button("Übungsaufnahme erzeugen");
        generateBtn.setOnClickListener(v -> generate());
        root.addView(generateBtn, matchWrap(dp(12)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, matchWrap(dp(4)));
        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, matchWrap(0));

        playerBox = new LinearLayout(this);
        playerBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(playerBox, matchWrap(dp(12)));
        playBtn = button("▶  Abspielen");
        playBtn.setOnClickListener(v -> togglePlay());
        playerBox.addView(playBtn, matchWrap(0));
        posBar = new SeekBar(this);
        posBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(p);
                updateTime();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        playerBox.addView(posBar, matchWrap(dp(4)));
        timeView = new TextView(this);
        timeView.setGravity(Gravity.END);
        playerBox.addView(timeView, matchWrap(0));

        saveMp3Btn = button("MP3 speichern");
        playerBox.addView(saveMp3Btn, matchWrap(dp(8)));
        saveMp3Btn.setOnClickListener(v -> save(REQ_SAVE_MP3, "audio/mpeg", baseName + ".mp3"));

        // MIDI and ABC need no rendering: available as soon as the notes are read
        exportRow = new LinearLayout(this);
        exportRow.setOrientation(LinearLayout.HORIZONTAL);
        saveMidiBtn = button("MIDI speichern");
        saveAbcBtn = button("ABC speichern");
        exportRow.addView(saveMidiBtn, weight());
        exportRow.addView(saveAbcBtn, weight());
        root.addView(exportRow, matchWrap(dp(8)));
        saveMidiBtn.setOnClickListener(v -> save(REQ_SAVE_MIDI, "audio/midi", baseName + ".mid"));
        saveAbcBtn.setOnClickListener(v -> save(REQ_SAVE_ABC, "text/plain", baseName + ".abc"));

        lyricsView = new TextView(this);
        lyricsView.setTextSize(14);
        lyricsView.setTextIsSelectable(true);
        root.addView(lyricsView, matchWrap(dp(16)));

        return scroll;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private double tempoFactor() {
        return 0.5 + tempoBar.getProgress() * 0.05;
    }

    /** Spinner: 0 = all equal, then per voice "X betont", "nur X". */
    private double[] voiceGains() {
        if (score == null) return null;
        int n = score.voices.size();
        double[] g = new double[n];
        int sel = emphasis.getSelectedItemPosition();
        for (int i = 0; i < n; i++) g[i] = 1.0;
        if (sel <= 0) return g;
        int v = (sel - 1) / 2;
        boolean only = (sel - 1) % 2 == 1;
        for (int i = 0; i < n; i++) g[i] = i == v ? 1.0 : (only ? 0.0 : 0.3);
        return g;
    }

    private void refresh() {
        boolean has = score != null && score.noteCount() > 0;
        tempoLabel.setText(String.format(Locale.GERMANY, "Tempo: %d %%%s", Math.round(tempoFactor() * 100),
                score != null ? String.format(Locale.GERMANY, "  (♩ = %d)",
                        Math.round(score.tempoBpm * tempoFactor())) : ""));
        generateBtn.setEnabled(has && !busy);
        tempoBar.setEnabled(!busy);
        emphasis.setEnabled(has && !busy);
        countIn.setEnabled(!busy);
        boolean ready = mp3 != null && !busy;
        playerBox.setVisibility(ready ? View.VISIBLE : View.GONE);
        saveMidiBtn.setEnabled(has && !busy);
        saveAbcBtn.setEnabled(has && !busy);
        exportRow.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void optionsChanged() {
        if (tempoLabel == null || generateBtn == null || playerBox == null || exportRow == null) return;
        if (mp3 != null && !busy) statusView.setText("Einstellungen geändert – zum Anwenden neu erzeugen.");
        refresh();
    }

    // ------------------------------------------------------------------ input

    private void open(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        startActivityForResult(i, req);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            load(intent.getData(), null);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (stream != null) load(stream, null);
            else if (text != null) loadAbcText(text, "ABC-Text");
        }
    }

    private void pasteDialog() {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(13);
        edit.setMinLines(8);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setHint("X:1\nT:Titel\nL:1/4\nK:C\nC D E F | G4 |]");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null && t.toString().contains("K:")) edit.setText(t);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("ABC-Notation")
                .setView(edit)
                .setPositiveButton("Übernehmen", (d, w) -> loadAbcText(edit.getText().toString(), "ABC-Text"))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void load(Uri uri, Integer req) {
        if (busy) return;
        String name = displayName(uri);
        setBusy(true, "Lese " + name + " …");
        worker.execute(() -> {
            try {
                byte[] data = readAll(uri);
                boolean pdf = data.length > 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
                Score s;
                if (pdf) {
                    s = PdfScoreExtractor.extract(PdfReader.read(new java.io.ByteArrayInputStream(data)));
                } else {
                    s = AbcParser.parse(new String(data, StandardCharsets.UTF_8));
                }
                ui.post(() -> scoreLoaded(s, stripExt(name)));
            } catch (Throwable e) {
                ui.post(() -> failed("Datei konnte nicht gelesen werden: " + e.getMessage()));
            }
        });
    }

    private void loadAbcText(String text, String name) {
        try {
            scoreLoaded(AbcParser.parse(text), name);
        } catch (Throwable e) {
            failed("ABC-Text konnte nicht gelesen werden: " + e.getMessage());
        }
    }

    private void scoreLoaded(Score s, String name) {
        setBusy(false, "");
        score = s;
        baseName = safeName(s.title != null && !s.title.isEmpty() ? s.title : name);
        mp3 = null;
        releasePlayer();

        StringBuilder info = new StringBuilder();
        info.append(s.title.isEmpty() ? name : s.title).append('\n');
        info.append(String.format(Locale.GERMANY, "Tempo ♩ = %d · Dauer %s · %d Noten\n",
                Math.round(s.tempoBpm), duration(s.end() * 60 / s.tempoBpm), s.noteCount()));
        for (Score.Voice v : s.voices) {
            int lo = 127, hi = 0;
            for (Score.Note n : v.notes) for (int p : n.pitches) { lo = Math.min(lo, p); hi = Math.max(hi, p); }
            info.append(String.format(Locale.GERMANY, "• %s: %d Noten, Umfang %s – %s\n",
                    v.name, v.notes.size(), noteName(lo), noteName(hi)));
        }
        infoView.setText(info.toString().trim());
        warnView.setText(s.warnings.isEmpty() ? "" : "Hinweise:\n• " + String.join("\n• ", s.warnings));

        StringBuilder ly = new StringBuilder();
        for (Score.Voice v : s.voices) {
            String t = v.lyrics();
            if (!t.isEmpty()) ly.append(v.name).append(": ").append(t).append("\n\n");
        }
        lyricsView.setText(ly.length() > 0 ? "Erkannter Text\n" + ly.toString().trim() : "");

        List<String> items = new ArrayList<>();
        items.add("Alle Stimmen gleich");
        for (Score.Voice v : s.voices) {
            items.add(v.name + " betont");
            items.add("Nur " + v.name);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        emphasis.setAdapter(ad);
        statusView.setText(s.noteCount() > 0 ? "Bereit." : "");
        refresh();
    }

    private void failed(String msg) {
        setBusy(false, "");
        warnView.setText(msg);
        refresh();
    }

    // ------------------------------------------------------------------ render

    private void generate() {
        if (score == null || busy) return;
        releasePlayer();
        final Score s = score;
        final ChoirSynth.Options o = new ChoirSynth.Options();
        o.tempoFactor = tempoFactor();
        o.voiceGain = voiceGains();
        o.countIn = countIn.isChecked();
        setBusy(true, "Erzeuge Chorklang …");
        worker.execute(() -> {
            try {
                short[] pcm = ChoirSynth.render(s, o, p -> ui.post(() -> progress.setProgress(p * 7 / 10)));
                ui.post(() -> statusView.setText("Kodiere MP3 …"));
                byte[] data = Mp3Encoder.encodeStereo(pcm, ChoirSynth.SAMPLE_RATE, 160,
                        p -> ui.post(() -> progress.setProgress(70 + p * 3 / 10)));
                try (FileOutputStream f = new FileOutputStream(mp3File)) {
                    f.write(data);
                }
                ui.post(() -> {
                    mp3 = data;
                    setBusy(false, String.format(Locale.GERMANY, "Fertig: %s, %d KB.",
                            duration(pcm.length / 2.0 / ChoirSynth.SAMPLE_RATE), data.length / 1024));
                    preparePlayer();
                    refresh();
                });
            } catch (Throwable e) {
                ui.post(() -> failed("Erzeugen fehlgeschlagen: " + e));
            }
        });
    }

    private void setBusy(boolean b, String status) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        progress.setProgress(0);
        statusView.setText(status);
        refresh();
    }

    // ------------------------------------------------------------------ player

    private void preparePlayer() {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(mp3File.getAbsolutePath());
            player.prepare();
            posBar.setMax(player.getDuration());
            posBar.setProgress(0);
            player.setOnCompletionListener(mp -> {
                playBtn.setText("▶  Abspielen");
                posBar.setProgress(0);
            });
            updateTime();
        } catch (IOException e) {
            warnView.setText("Abspielen nicht möglich: " + e.getMessage());
        }
    }

    private void togglePlay() {
        if (player == null) preparePlayer();
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            playBtn.setText("▶  Abspielen");
        } else {
            player.start();
            playBtn.setText("❚❚  Pause");
            ui.post(ticker);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                posBar.setProgress(player.getCurrentPosition());
                ui.postDelayed(this, 200);
            }
        }
    };

    private void updateTime() {
        if (player == null) {
            timeView.setText("");
            return;
        }
        timeView.setText(duration(posBar.getProgress() / 1000.0) + " / " + duration(player.getDuration() / 1000.0));
    }

    private void releasePlayer() {
        ui.removeCallbacks(ticker);
        if (player != null) {
            player.release();
            player = null;
        }
        if (playBtn != null) playBtn.setText("▶  Abspielen");
    }

    // ------------------------------------------------------------------ save

    private void save(int req, String mime, String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (req) {
            case REQ_OPEN_PDF:
            case REQ_OPEN_ABC:
                load(uri, req);
                break;
            case REQ_SAVE_MP3:
                if (mp3 != null) write(uri, mp3, "MP3");
                break;
            case REQ_SAVE_MIDI:
                if (score != null) write(uri, MidiWriter.write(score, tempoFactor(), voiceGains()), "MIDI");
                break;
            case REQ_SAVE_ABC:
                if (score != null) write(uri, AbcWriter.write(score).getBytes(StandardCharsets.UTF_8), "ABC");
                break;
            default:
                break;
        }
    }

    private void write(Uri uri, byte[] bytes, String what) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("kein Ziel");
            os.write(bytes);
            Toast.makeText(this, what + " gespeichert.", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, what + " konnte nicht gespeichert werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ helpers

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Datei nicht lesbar");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
            // fall back to the last path segment
        }
        if (name == null) name = uri.getLastPathSegment();
        return name != null ? name : "Noten";
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "Uebung" : s;
    }

    private static String duration(double sec) {
        int s = (int) Math.round(sec);
        return String.format(Locale.GERMANY, "%d:%02d", s / 60, s % 60);
    }

    private static String noteName(int midi) {
        String[] n = {"C", "Cis", "D", "Es", "E", "F", "Fis", "G", "Gis", "A", "B", "H"};
        return n[Math.floorMod(midi, 12)] + (midi / 12 - 1);
    }
}
