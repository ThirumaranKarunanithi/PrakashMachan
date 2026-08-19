package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.auth.TenantGuard;
import com.ledgerintegrity.platform.dashboard.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService service;
    private final CurrentUser currentUser;
    private final TenantGuard guard;

    public DashboardController(DashboardService service, CurrentUser currentUser, TenantGuard guard) {
        this.service = service;
        this.currentUser = currentUser;
        this.guard = guard;
    }

    /** BRD §18.1: firm portfolio dashboard, ranked by open HIGH items then exposure. */
    @GetMapping("/dashboard")
    public List<DashboardService.PortfolioRow> portfolio() {
        return service.portfolio(currentUser.firmId());
    }

    /** BRD §18.1: risk explorer — what drives the currently open risk. */
    @GetMapping("/engagements/{id}/risk-explorer")
    public DashboardService.Explorer explorer(@PathVariable UUID id) {
        guard.engagement(id);
        return service.explorer(id);
    }
}
