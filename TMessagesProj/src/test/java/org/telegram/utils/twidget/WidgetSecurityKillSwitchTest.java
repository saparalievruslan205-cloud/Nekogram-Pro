package org.telegram.utils.twidget;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class)
public class WidgetSecurityKillSwitchTest {

    @Test
    public void widgetEngineIsHardDisabledInPro() {
        assertTrue(WidgetSandboxSession.disabledByConfig());
    }

    @Test
    public void widgetMediaDoesNotCreateOrResolveRuntimes() {
        assertNull(WidgetMedia.find("tg-widget:0/1/2/3"));
        assertNull(WidgetMedia.richMessage(null, new TLRPC.TL_messageMediaWidget()));
        assertNull(WidgetMedia.previewRichMessage(0, new TLRPC.TL_botInlineResult()));
    }

    @Test
    public void widgetSandboxSessionRefusesToStart() throws Exception {
        WidgetSandboxSession session = new WidgetSandboxSession(
                RuntimeEnvironment.getApplication(),
                "<html></html>",
                "{}",
                1,
                "en-US",
                "{}"
        );
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                session.start(300, false);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        worker.join(5000);
        assertTrue("Expected IllegalStateException", thrown.get() instanceof IllegalStateException);
        assertTrue(thrown.get().getMessage().contains("disabled"));
    }
}
