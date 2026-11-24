package com.jarvis.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground service that listens for the wake word "Jarvis" and runs the command after it. */
public class JarvisService extends Service implements RecognitionListener {
    static final String CHANNEL = "jarvis_channel";
    static volatile boolean running = false;

    private static final String[] WAKE = {"jarvis", "jarvice", "jarvas", "jervis"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private Intent recIntent;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean speaking = false;
    private int awaitLeft = 0; // >0 means the next utterance is a command without the wake word
    private CommandProcessor processor;

    @Override
    public void onCreate() {
        super.onCreate();
        processor = new CommandProcessor(this);

        NotificationChannel ch = new NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("en", "IN"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) { resumeAfterSpeech(); }
                    @Override public void onError(String id) { resumeAfterSpeech(); }
                });
                ttsReady = true;
            }
        });

        recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        recIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Jarvis is listening")
                .setContentText("Say \"Jarvis\" followed by a command")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
        running = true;
        JarvisLog.add("Jarvis started. Say \"Jarvis\" and a command.");
        handler.post(this::startListening);
        return START_STICKY;
    }

    // ------------------------------------------------------------------ listening

    private void startListening() {
        if (!running || speaking) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisLog.add("No speech recognition service found on this phone.");
            stopSelf();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        recognizer.startListening(recIntent);
    }

    private void restart(long delayMs) {
        handler.postDelayed(this::startListening, delayMs);
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String cmd = null;
        boolean wake = false;
        if (r != null && !r.isEmpty()) {
            if (awaitLeft > 0) {
                cmd = r.get(0);
                awaitLeft = 0;
            } else {
                for (String s : r) {
                    String l = s.toLowerCase(Locale.ROOT);
                    for (String w : WAKE) {
                        int i = l.indexOf(w);
                        if (i >= 0) {
                            wake = true;
                            cmd = l.substring(i + w.length()).trim();
                            break;
                        }
                    }
                    if (wake) break;
                }
            }
        }
        if (cmd != null && !cmd.isEmpty()) {
            runCommand(cmd);
            restart(300);
        } else if (wake) {
            awaitLeft = 2;
            JarvisLog.add("Wake word heard.");
            speak("Yes?");
        } else {
            if (awaitLeft > 0) awaitLeft--;
            restart(300);
        }
    }

    @Override
    public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            JarvisLog.add("Microphone permission is missing.");
            stopSelf();
            return;
        }
        if (awaitLeft > 0) awaitLeft--;
        long delay = 400;
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            if (recognizer != null) recognizer.cancel();
            delay = 1200;
        }
        restart(delay);
    }

    // ------------------------------------------------------------------ commands and speech

    private void runCommand(String cmd) {
        JarvisLog.add("Heard: " + cmd);
        worker.execute(() -> {
            String reply;
            try {
                reply = processor.execute(cmd);
            } catch (Exception e) {
                reply = "Something went wrong.";
            }
            final String text = reply;
            JarvisLog.add("Jarvis: " + text);
            handler.post(() -> {
                if (processor.expectsFollowUp()) awaitLeft = 2;
                speak(text);
            });
        });
    }

    private void speak(String text) {
        if (!ttsReady) {
            restart(300);
            return;
        }
        speaking = true;
        if (recognizer != null) recognizer.cancel();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
    }

    private void resumeAfterSpeech() {
        handler.post(() -> {
            speaking = false;
            startListening();
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
        }
        worker.shutdown();
        JarvisLog.add("Jarvis stopped.");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}
}
