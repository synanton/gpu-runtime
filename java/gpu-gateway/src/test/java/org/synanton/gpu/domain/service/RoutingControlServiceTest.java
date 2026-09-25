package org.synanton.gpu.domain.service;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ControlOverride;
import org.synanton.gpu.domain.port.out.RoutingControlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-K8S-38: effective routing control = configuration (floor) AND persisted runtime overrides. */
class RoutingControlServiceTest {

    private final Map<String, ControlOverride> rows = new HashMap<>();
    private boolean storeDown = false;
    private final RoutingControlStore store = new RoutingControlStore() {
        @Override public Map<String, ControlOverride> all() {
            if (storeDown) throw new IllegalStateException("db down");
            return rows;
        }
        @Override public void set(String scope, boolean enabled, String by, String reason) {
            rows.put(scope, new ControlOverride(scope, enabled, by, reason, Instant.now()));
        }
    };
    private GpuGatewayProperties p;
    private RoutingControlService control;

    @BeforeEach
    void setUp() {
        p = new GpuGatewayProperties();
        var mock = new GpuGatewayProperties.ProviderConfig();
        mock.setBaseUrl("http://m/v1");
        mock.setApiKey("k");
        p.getProviders().put("mock", mock);
        var off = new GpuGatewayProperties.ProviderConfig();
        off.setEnabled(false);
        p.getProviders().put("off", off);
        control = new RoutingControlService(p, store);
    }

    @Test
    void defaultsFollowConfiguration() {
        assertThat(control.externalRoutingEnabled()).isTrue();
        assertThat(control.providerEnabled("mock")).isTrue();
        assertThat(control.providerEnabled("off")).isFalse();
        assertThat(control.providerEnabled("unknown")).isFalse();
    }

    @Test
    void runtimeKillSwitchIsPersistedAndAudited() {
        control.setExternalRouting(false, "admin-1", "incident 42");

        assertThat(control.externalRoutingEnabled()).isFalse();
        assertThat(rows.get(ControlOverride.EXTERNAL_ROUTING).updatedBy()).isEqualTo("admin-1");
        // a fresh service (restart / another replica) reads the same persisted state
        assertThat(new RoutingControlService(p, store).externalRoutingEnabled()).isFalse();

        control.setExternalRouting(true, "admin-1", "resolved");
        assertThat(control.externalRoutingEnabled()).isTrue();
    }

    @Test
    void runtimeProviderDisable() {
        control.setProviderEnabled("mock", false, "admin-1", "provider outage");
        assertThat(control.providerEnabled("mock")).isFalse();
    }

    @Test
    void configurationIsTheFloor() {
        p.getRouting().setExternalEnabled(false);
        assertThatThrownBy(() -> control.setExternalRouting(true, "a", "r"))
                .isInstanceOfSatisfying(RoutingDeniedException.class, e -> assertThat(e.getCode()).isEqualTo("config_disabled"));
        assertThatThrownBy(() -> control.setProviderEnabled("off", true, "a", "r"))
                .isInstanceOfSatisfying(RoutingDeniedException.class, e -> assertThat(e.getCode()).isEqualTo("config_disabled"));
    }

    @Test
    void reasonIsRequiredAndProviderMustExist() {
        assertThatThrownBy(() -> control.setExternalRouting(false, "a", " "))
                .isInstanceOfSatisfying(RoutingDeniedException.class, e -> assertThat(e.getCode()).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> control.setProviderEnabled("nope", false, "a", "r"))
                .isInstanceOfSatisfying(RoutingDeniedException.class, e -> assertThat(e.getCode()).isEqualTo("provider_not_configured"));
    }

    @Test
    void unreadableStateFailsClosed() {
        storeDown = true;
        assertThatThrownBy(() -> control.externalRoutingEnabled())
                .isInstanceOfSatisfying(RoutingDeniedException.class, e -> assertThat(e.getCode()).isEqualTo("routing_state_unavailable"));
    }
}
