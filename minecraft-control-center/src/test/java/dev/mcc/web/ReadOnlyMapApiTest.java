package dev.mcc.web;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyMapApiTest {
    @Test void everyFormerEditorEndpointIsRetiredIncludingUnknownBuildOperations(){
        for(String path:List.of("/api/build","/api/build/preview","/api/build/actions","/api/build/jobs/0",
                "/api/build/catalog","/api/build/history","/api/build/future-write","/api/map/detail","/api/map/region"))
            assertTrue(WebServer.retiredMapEndpoint(path),path);
    }

    @Test void retirementDoesNotInterceptReadOnlyMapOrOtherFeatures(){
        for(String path:List.of("/api/map","/api/map/terrain","/api/map/index","/api/worlds","/api/auth/account",
                "/api/actions/0","/api/automations/catalog","/api/building"))
            assertFalse(WebServer.retiredMapEndpoint(path),path);
    }
}
