package uk.mindburner.projector;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK = 1, MIC = 2;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private PermissionRequest pendingPerm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Object> midiOpen = new ArrayList<>();   // keeps MIDI ports alive
    private final List<String> midiNames = new ArrayList<>();
    private boolean midiStarted = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (!midiStarted) { midiStarted = true; startMidi(); } else midiStatus();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "Choose images or videos"), PICK);
                return true;
            }
            @Override public void onPermissionRequest(final PermissionRequest req) {
                ui.post(() -> {
                    boolean audio = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                    if (!audio) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
                    }
                });
            }
        });
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            // https origin so saved layouts, images and the microphone work
            web.loadDataWithBaseURL("https://projector.local/", out.toString("UTF-8"), "text/html", "UTF-8", null);
        } catch (Exception e) { e.printStackTrace(); }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        immersive();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && pending != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int c = data.getClipData().getItemCount();
                    r = new Uri[c];
                    for (int k = 0; k < c; k++) r[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) r = new Uri[]{data.getData()};
            }
            pending.onReceiveValue(r);
            pending = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == MIC && pendingPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
                pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    /* ---------- USB MIDI ---------- */
    @SuppressWarnings("deprecation")
    private void startMidi() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) { js("window.midiStatus&&midiStatus('This phone has no MIDI support')"); return; }
        final MidiManager mm = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        if (mm == null) return;
        for (MidiDeviceInfo info : mm.getDevices()) openMidi(mm, info);
        mm.registerDeviceCallback(new MidiManager.DeviceCallback() {
            @Override public void onDeviceAdded(MidiDeviceInfo info) { openMidi(mm, info); }
            @Override public void onDeviceRemoved(MidiDeviceInfo info) { midiNames.remove(midiName(info)); midiStatus(); }
        }, ui);
        midiStatus();
    }

    private String midiName(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String n = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (n == null) n = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        return n == null ? "MIDI device" : n;
    }

    private void openMidi(MidiManager mm, final MidiDeviceInfo info) {
        if (info.getOutputPortCount() == 0) return;
        mm.openDevice(info, dev -> {
            if (dev == null) return;
            midiOpen.add(dev);
            for (int p = 0; p < info.getOutputPortCount(); p++) {
                MidiOutputPort op = dev.openOutputPort(p);
                if (op == null) continue;
                midiOpen.add(op);
                op.connect(new Parser());
            }
            midiNames.add(midiName(info));
            midiStatus();
        }, ui);
    }

    private void midiStatus() {
        String s = midiNames.isEmpty() ? "No MIDI device connected. Plug one in with a USB-C adapter."
                                       : "Connected: " + TextUtils.join(", ", midiNames);
        js("window.midiStatus&&midiStatus(" + JSONObject.quote(s) + ")");
    }

    private void js(final String code) { ui.post(() -> web.evaluateJavascript(code, null)); }

    /* turns the raw MIDI byte stream into note / CC / program messages for the page */
    private class Parser extends MidiReceiver {
        private int run = 0, n = 0;
        private final int[] d = new int[2];
        @Override public void onSend(byte[] msg, int off, int count, long ts) {
            for (int k = off; k < off + count; k++) {
                int b = msg[k] & 0xFF;
                if (b >= 0xF8) continue;                       // clock and other real-time bytes
                if (b >= 0x80) { run = b >= 0xF0 ? 0 : b; n = 0; continue; }
                if (run == 0) continue;
                d[n++] = b;
                int t = run & 0xF0, need = (t == 0xC0 || t == 0xD0) ? 1 : 2;
                if (n >= need) {
                    js("window.onMidi&&onMidi(" + run + "," + d[0] + "," + (need == 2 ? d[1] : 0) + ")");
                    n = 0;
                }
            }
        }
    }

    /* back: leave projection, then close an open page, otherwise close the app */
    private void handleBack() {
        web.evaluateJavascript("window.exitProj ? String(exitProj()) : 'false'", r -> {
            if (!"\"true\"".equals(r)) finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
