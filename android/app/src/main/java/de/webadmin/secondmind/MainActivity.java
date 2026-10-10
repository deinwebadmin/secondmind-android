package de.webadmin.secondmind;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Eigene Plugins registrieren, bevor die Bridge startet
        registerPlugin(SecondMindPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
