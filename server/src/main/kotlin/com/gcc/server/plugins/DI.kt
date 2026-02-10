package com.gcc.server.plugins

import com.gcc.server.services.*
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.core.logger.Level

/**
 * Koin dependency injection setup.
 *
 * This module declares all service singletons. Each service is created once
 * and shared across all route handlers for the lifetime of the server.
 *
 * To add a new service:
 * 1. Create the service class in services/
 * 2. Add a `single { YourService() }` line below
 * 3. In your route file, access it with: val yourService = koinGet<YourService>()
 */
val appModule = module {
    // Core services (no dependencies)
    single { WorkerService() }
    single { RouteService() }
    single { BlockService() }
    single { JobService() }
    single { ServiceAreaService() }
    single { SpotService() }
    single { WalletService() }
    single { VolunteerService() }
    single { GamificationService() }
    single { FeedService() }

    // Auth (depends on WorkerService)
    single { AuthService(workerService = get()) }

    // Services with dependencies
    single { BountyService(walletService = get()) }

    // Admin services
    single { AdminService(workerService = get()) }
    single { DisputeService(walletService = get()) }
    single {
        AnalyticsService(
            workerService = get(),
            jobService = get(),
            bountyService = get()
        )
    }

    // Composite services (depend on other services)
    single {
        StatsService(
            workerService = get(),
            blockService = get(),
            jobService = get(),
            routeService = get(),
            serviceAreaService = get(),
            gamificationService = get()
        )
    }
}

fun Application.configureDI() {
    install(Koin) {
        printLogger(Level.INFO)
        modules(appModule)
    }
}
