package esimtile.app;

import android.os.Bundle;

interface ISim {
    void destroy() = 16777114;
    Bundle listProfiles() = 1;
    oneway void setEnabled(int subId, boolean on, boolean keepRoamingOff) = 2;
    oneway void fixRoaming(int subId) = 3;
}
