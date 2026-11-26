package android.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.app.Application;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HandlerToastTest {
    private Log.Sink previousSink;

    @BeforeEach
    void captureLog() {
        previousSink = Log.sink();
    }

    @AfterEach
    void restoreLog() {
        Log.setSink(previousSink);
    }

    @Test
    void handlerPostsImmediatelyOnMainLooper() {
        AtomicBoolean ran = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());

        assertSame(Looper.getMainLooper(), handler.getLooper());
        assertTrue(handler.post(() -> ran.set(true)));
        assertTrue(ran.get());
    }

    @Test
    void toastExposesMessageTextViewAndLogsOnShow() {
        List<String> logged = new ArrayList<>();
        Log.setSink((priority, tag, message) -> logged.add(tag + ":" + message));

        Toast toast = Toast.makeText(new Application(), "Staged TeamCode Load", Toast.LENGTH_SHORT);
        TextView messageView = toast.getView().findViewById(0x0102000b);
        assertNotNull(messageView);
        assertEquals("Staged TeamCode Load", messageView.getText());
        messageView.setTextSize(18f);
        assertEquals(18f, messageView.getTextSize());

        toast.show();

        assertEquals(List.of("Toast:Staged TeamCode Load"), logged);
    }
}
