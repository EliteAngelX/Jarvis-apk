package com.jarvis.assistant;

import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a spoken command into an action and returns a short reply to speak. */
public class CommandProcessor {
    private static final Pattern NUM = Pattern.compile("(\\d{1,3})");

    private final Context ctx;
    private final SharedPreferences prefs;
    private String pending;

    public CommandProcessor(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    /** True when the last reply asked a yes/no question (e.g. confirm reboot). */
    public boolean expectsFollowUp() {
        return pending != null;
    }

    private boolean useRoot() {
        return prefs.getBoolean("root", false) && RootShell.hasRoot();
    }

    // ------------------------------------------------------------------ main entry

    public String execute(String raw) {
        String c = normalize(raw);
        if (c.isEmpty()) return "I am listening.";

        if (pending != null) {
            String p = pending;
            pending = null;
            if (c.startsWith("yes") || c.startsWith("confirm") || c.startsWith("do it")) {
                return confirmed(p);
            }
            return "Cancelled.";
        }

        if (c.matches(".*\\b(hello|hi|hey there)\\b.*") && c.length() < 15) return "Hello. How can I help?";
        if (c.contains("what can you do") || c.equals("help")) {
            return "I can open apps, play music, control volume and brightness, and switch Wi-Fi, "
                    + "Bluetooth, flashlight, airplane mode, mobile data, location and more on or off.";
        }
        if (c.contains("what time") || c.contains("time is it") || c.contains("current time")) {
            return "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        }
        if (c.contains("battery")) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            return "Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent.";
        }

        String r = tryToggle(c);
        if (r != null) return r;

        if (c.contains("brightness")) return brightness(c);
        if (c.contains("volume") || c.contains("mute")) return volume(c);

        r = tryMusic(c);
        if (r != null) return r;

        r = trySystem(c);
        if (r != null) return r;

        Matcher open = Pattern.compile("^(open|launch|start|run)\\s+(.+)$").matcher(c);
        if (open.matches()) return openApp(open.group(2));

        return "Sorry, I did not understand: " + c;
    }

    // ------------------------------------------------------------------ toggles

    private String tryToggle(String c) {
        boolean on = has(c, "on") || has(c, "enable") || has(c, "activate");
        boolean off = has(c, "off") || has(c, "disable") || has(c, "deactivate");
        if (on == off) return null;

        if (c.contains("wifi") || c.contains("wi fi") || has(c, "wireless")) return wifi(on);
        if (c.contains("bluetooth")) return bluetooth(on);
        if (has(c, "flashlight") || c.contains("flash light") || has(c, "torch") || has(c, "light")) return torch(on);
        if (c.contains("airplane") || c.contains("aeroplane") || c.contains("flight mode")) return airplane(on);
        if (c.contains("mobile data") || c.contains("cellular") || has(c, "data") || c.contains("internet")) return data(on);
        if (c.contains("location") || has(c, "gps")) return location(on);
        if (c.contains("do not disturb") || has(c, "dnd") || c.contains("silent mode")) return dnd(on);
        if (c.contains("rotat")) return rotation(on);
        if (has(c, "nfc")) return nfc(on);
        return null;
    }

    private String wifi(boolean on) {
        if (useRoot()) {
            RootShell.run("svc wifi " + (on ? "enable" : "disable"));
            return "Wi-Fi turned " + state(on) + ".";
        }
        String action = Build.VERSION.SDK_INT >= 29
                ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS;
        launch(new Intent(action));
        return "Android does not let apps switch Wi-Fi without root. I opened the panel. "
                + "Turn on root mode in the app for direct control.";
    }

    private String bluetooth(boolean on) {
        if (useRoot()) {
            String s = on ? "enable" : "disable";
            RootShell.run("svc bluetooth " + s + " || cmd bluetooth_manager " + s);
            return "Bluetooth turned " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        return "Android does not let apps switch Bluetooth without root. I opened Bluetooth settings.";
    }

    private String airplane(boolean on) {
        if (useRoot()) {
            if (Build.VERSION.SDK_INT >= 30) {
                RootShell.run("cmd connectivity airplane-mode " + (on ? "enable" : "disable"));
            } else {
                RootShell.run("settings put global airplane_mode_on " + (on ? 1 : 0)
                        + "; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + on);
            }
            return "Airplane mode " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        return "Airplane mode needs root. I opened its settings.";
    }

    private String data(boolean on) {
        if (useRoot()) {
            RootShell.run("svc data " + (on ? "enable" : "disable"));
            return "Mobile data turned " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
        return "Mobile data needs root. I opened network settings.";
    }

    private String location(boolean on) {
        if (useRoot()) {
            RootShell.run("settings put secure location_mode " + (on ? 3 : 0)
                    + "; cmd location set-location-enabled " + on);
            return "Location turned " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        return "Location needs root. I opened location settings.";
    }

    private String nfc(boolean on) {
        if (useRoot()) {
            RootShell.run("svc nfc " + (on ? "enable" : "disable"));
            return "NFC turned " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_NFC_SETTINGS));
        return "NFC needs root. I opened NFC settings.";
    }

    private String dnd(boolean on) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(on
                    ? NotificationManager.INTERRUPTION_FILTER_NONE
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
            return "Do not disturb " + state(on) + ".";
        }
        if (useRoot()) {
            RootShell.run("cmd notification set_dnd " + (on ? "on" : "off"));
            return "Do not disturb " + state(on) + ".";
        }
        launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        return "Please give Jarvis do not disturb access, then ask again.";
    }

    private String rotation(boolean on) {
        if (useRoot()) {
            RootShell.run("settings put system accelerometer_rotation " + (on ? 1 : 0));
        } else if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        } else {
            return askWriteSettings();
        }
        return "Auto rotate " + state(on) + ".";
    }

    private String torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, on);
                    return "Flashlight " + state(on) + ".";
                }
            }
            return "This phone has no flashlight.";
        } catch (Exception e) {
            return "I could not control the flashlight.";
        }
    }

    // ------------------------------------------------------------------ volume / brightness

    private String volume(String c) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Matcher m = NUM.matcher(c);
        if (m.find()) {
            int pct = Math.min(100, Integer.parseInt(m.group(1)));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max * pct / 100, AudioManager.FLAG_SHOW_UI);
            return "Volume set to " + pct + " percent.";
        }
        if (c.contains("unmute")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, AudioManager.FLAG_SHOW_UI);
            return "Unmuted.";
        }
        if (c.contains("mute")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
            return "Muted.";
        }
        if (c.contains("max") || c.contains("full")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI);
            return "Volume at maximum.";
        }
        if (has(c, "up") || c.contains("increase") || c.contains("raise") || c.contains("louder")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return "Volume up.";
        }
        if (has(c, "down") || c.contains("decrease") || c.contains("lower") || c.contains("quieter")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return "Volume down.";
        }
        return "Say volume up, volume down, mute, or a percent.";
    }

    private String brightness(String c) {
        int cur;
        try {
            cur = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception e) {
            cur = 128;
        }
        int level;
        Matcher m = NUM.matcher(c);
        if (m.find()) {
            level = Math.min(100, Integer.parseInt(m.group(1))) * 255 / 100;
        } else if (c.contains("max") || c.contains("full")) {
            level = 255;
        } else if (has(c, "up") || c.contains("increase") || c.contains("raise") || c.contains("brighter")) {
            level = cur + 60;
        } else if (has(c, "down") || c.contains("decrease") || c.contains("lower") || c.contains("dim")) {
            level = cur - 60;
        } else {
            return "Say brightness up, brightness down, or a percent.";
        }
        level = Math.max(1, Math.min(255, level));

        if (useRoot()) {
            RootShell.run("settings put system screen_brightness_mode 0; settings put system screen_brightness " + level);
        } else if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, level);
        } else {
            return askWriteSettings();
        }
        return "Brightness set to " + (level * 100 / 255) + " percent.";
    }

    private String askWriteSettings() {
        launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName())));
        return "Please allow Jarvis to modify system settings, then ask again.";
    }

    // ------------------------------------------------------------------ music

    private String tryMusic(String c) {
        if (c.startsWith("play ") || c.equals("play")) {
            String q = c.length() > 5 ? c.substring(5).trim() : "";
            String appName = null;
            Matcher on = Pattern.compile("^(.*) on ([\\p{L}\\p{N} ]+)$").matcher(q);
            if (on.matches()) {
                q = on.group(1).trim();
                appName = on.group(2).trim();
            }
            q = q.replaceFirst("^(some |the )?(music|songs?|track)( called| named| by)?\\s*", "").trim();
            if (q.isEmpty()) {
                key(KeyEvent.KEYCODE_MEDIA_PLAY);
                return "Playing music.";
            }
            return playSearch(q, appName == null ? null : findPackage(appName));
        }
        if (c.contains("next song") || c.contains("next track") || has(c, "skip") || c.equals("next")) {
            key(KeyEvent.KEYCODE_MEDIA_NEXT);
            return "Next track.";
        }
        if (c.contains("previous") || c.contains("last song")) {
            key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return "Previous track.";
        }
        if (has(c, "pause")) {
            key(KeyEvent.KEYCODE_MEDIA_PAUSE);
            return "Paused.";
        }
        if (has(c, "stop") && (c.contains("music") || c.contains("song") || c.contains("playing"))) {
            key(KeyEvent.KEYCODE_MEDIA_STOP);
            return "Stopped.";
        }
        if (has(c, "resume") || c.contains("continue playing")) {
            key(KeyEvent.KEYCODE_MEDIA_PLAY);
            return "Resuming.";
        }
        return null;
    }

    private String playSearch(String q, String pkg) {
        if (useRoot()) {
            // q only contains letters, digits and spaces (see normalize), so it is safe inside quotes.
            RootShell.run("am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query '" + q
                    + "' --es android.intent.extra.focus 'vnd.android.cursor.item/*'"
                    + (pkg != null ? " -p " + pkg : ""));
            return "Playing " + q + ".";
        }
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        i.putExtra(SearchManager.QUERY, q);
        if (pkg != null) i.setPackage(pkg);
        if (!launch(i)) return "I could not find a music app to play that.";
        return "Playing " + q + ".";
    }

    private void key(int code) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    // ------------------------------------------------------------------ system actions

    private String trySystem(String c) {
        if (c.contains("lock screen") || c.contains("lock the phone") || c.contains("lock phone")) {
            return needRoot("input keyevent 26", "Locking.");
        }
        if (c.contains("screenshot") || c.contains("screen shot")) {
            return needRoot("input keyevent 120", "Taking a screenshot.");
        }
        if (c.contains("go home") || c.contains("home screen")) {
            if (useRoot()) {
                RootShell.run("input keyevent 3");
            } else {
                launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
            }
            return "Going home.";
        }
        if (c.contains("go back")) return needRoot("input keyevent 4", "Going back.");
        if (c.contains("recent apps") || c.contains("recents")) return needRoot("input keyevent 187", "Showing recent apps.");
        if (has(c, "reboot") || c.contains("restart phone") || c.contains("restart the phone")) {
            pending = "reboot";
            return "Do you want me to reboot the phone? Say yes to confirm.";
        }
        if (c.contains("power off") || c.contains("shut down") || c.contains("shutdown")) {
            pending = "poweroff";
            return "Do you want me to power off the phone? Say yes to confirm.";
        }
        return null;
    }

    private String confirmed(String action) {
        if (!useRoot()) return "Root mode is needed for that.";
        if (action.equals("reboot")) {
            RootShell.run("reboot");
            return "Rebooting.";
        }
        RootShell.run("reboot -p");
        return "Powering off.";
    }

    private String needRoot(String cmd, String ok) {
        if (!useRoot()) return "That needs root mode.";
        RootShell.run(cmd);
        return ok;
    }

    // ------------------------------------------------------------------ apps

    private String openApp(String spoken) {
        String name = spoken.replaceFirst("^the ", "").replaceFirst(" (app|application)$", "").trim();
        String pkg = findPackage(name);
        if (pkg == null) return "I could not find an app called " + name + ".";
        if (useRoot()) {
            RootShell.run("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
            return "Opening " + name + ".";
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null || !launch(i)) return "I could not open " + name + ".";
        return "Opening " + name + ".";
    }

    private String findPackage(String name) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        String want = normalize(name);
        String bestPkg = null;
        int bestScore = 0;
        int bestLen = Integer.MAX_VALUE;
        for (ResolveInfo ri : apps) {
            String label = normalize(String.valueOf(ri.loadLabel(pm)));
            int score = 0;
            if (label.equals(want)) score = 3;
            else if (label.startsWith(want)) score = 2;
            else if (label.contains(want) || (!label.isEmpty() && want.contains(label))) score = 1;
            if (score > bestScore || (score == bestScore && score > 0 && label.length() < bestLen)) {
                bestScore = score;
                bestLen = label.length();
                bestPkg = ri.activityInfo.packageName;
            }
        }
        return bestPkg;
    }

    // ------------------------------------------------------------------ helpers

    private boolean launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String state(boolean on) {
        return on ? "on" : "off";
    }

    private static boolean has(String c, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(c).find();
    }

    private static String normalize(String s) {
        String c = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        String prev;
        do {
            prev = c;
            c = c.replaceFirst("^(please|hey|ok|okay|can you|could you|will you)\\s+", "");
        } while (!c.equals(prev));
        return c;
    }
}
