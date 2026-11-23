package tw.nekomimi.nekogram.forward;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class)
public class ForwardItemTest {

    @Test
    public void noQuoteButtonSwitchesToNoCaptionWhenCaptionPresent() {
        assertEquals(ForwardItem.ID_FORWARD_NOCAPTION, ForwardItem.getNoQuoteForwardOption(true));
        assertEquals(ForwardItem.ID_FORWARD_NOQUOTE, ForwardItem.getNoQuoteForwardOption(false));
    }

    @Test
    public void regularForwardButtonStaysRegularWhenDedicatedNoQuoteButtonEnabled() {
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD, true, false));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD, true, true));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOQUOTE, true, false));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOQUOTE, true, true));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOCAPTION, true, false));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOCAPTION, true, true));
    }

    @Test
    public void singleForwardButtonAutoSwitchesNoQuoteToNoCaptionWhenCaptionPresent() {
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD, false, false));
        assertEquals(ForwardItem.ID_FORWARD, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD, false, true));
        assertEquals(ForwardItem.ID_FORWARD_NOQUOTE, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOQUOTE, false, false));
        assertEquals(ForwardItem.ID_FORWARD_NOCAPTION, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOQUOTE, false, true));
        assertEquals(ForwardItem.ID_FORWARD_NOQUOTE, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOCAPTION, false, false));
        assertEquals(ForwardItem.ID_FORWARD_NOCAPTION, ForwardItem.resolveForwardOption(ForwardItem.ID_FORWARD_NOCAPTION, false, true));
    }

    @Test
    public void hasCaptionHandlesNullAndEmptySafely() {
        assertFalse(ForwardItem.hasCaption(null));
        assertFalse(ForwardItem.hasCaption(Collections.emptyList()));
        assertFalse(ForwardItem.hasCaption(Collections.singletonList(null)));
        assertFalse(ForwardItem.hasCaption(null, null));
    }
}
