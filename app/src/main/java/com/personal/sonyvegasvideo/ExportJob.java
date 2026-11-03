package com.personal.sonyvegasvideo;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-scoped job; never retains an Activity across a Fold/rotation change. */
public final class ExportJob {
    public interface Listener { void changed(); }
    private static final ExportJob INSTANCE = new ExportJob();
    public static ExportJob get() { return INSTANCE; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AtomicBoolean cancelled = new AtomicBoolean();
    private Listener listener;
    // State is read and written only on the main thread.
    public boolean running;
    public int percent;
    public String message = "";
    public ClipExporter.Result result;

    private ExportJob() { }
    public void attach(Listener next) { listener = next; notifyChanged(); }
    public void detach(Listener previous) { if (listener == previous) listener = null; }
    private void notifyChanged() { if (listener != null) listener.changed(); }
    public void cancel() {
        if (!running) return;
        cancelled.set(true);
        message = "Отменяю экспорт…";
        notifyChanged();
    }
    public void start(Context owner, Uri source, long startUs, long endUs, boolean mute) {
        startTask(owner, source, startUs, endUs, mute, null);
    }
    public void startSequence(Context owner, Sequence sequence, boolean mute) {
        startTask(owner, null, 0, 0, mute, sequence);
    }
    private void startTask(Context owner, Uri source, long startUs, long endUs, boolean mute, Sequence sequence) {
        if (running) return;
        Context context = owner.getApplicationContext();
        AtomicBoolean token = new AtomicBoolean();
        cancelled = token;
        running = true; percent = 0; result = null; message = "Сохраняю MP4…";
        notifyChanged();
        worker.execute(() -> {
            ClipExporter.Result exported = null;
            String error = null;
            try {
                ClipExporter.Progress progress = value -> main.post(() -> { percent = value; notifyChanged(); });
                exported = sequence == null ? ClipExporter.export(context, source, startUs, endUs, mute, token, progress)
                        : SequenceExporter.export(context, sequence, mute, token, progress);
            } catch (CancellationException e) {
                error = "Экспорт отменён. Незавершённый файл удалён.";
            } catch (Exception e) {
                error = "Ошибка экспорта: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            ClipExporter.Result finished = exported;
            String failure = error;
            main.post(() -> {
                running = false; result = finished;
                message = failure != null ? failure : "Готово! Галерея → Фильмы → Sony Vegas Video\n"
                        + "Фактические границы: " + ClipMath.clock(finished.startUs / 1000)
                        + " — " + ClipMath.clock(finished.endUs / 1000);
                notifyChanged();
            });
        });
    }
}
