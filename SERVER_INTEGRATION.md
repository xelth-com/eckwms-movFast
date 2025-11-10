# eckWMS - Server Integration Guide

**Version: 1.0**

This document describes how the `eckwms-movfast` Android application implements the technical requirements defined in the `eckwms` server project's `ANDROID_INTEGRATION.md` file.

## 1. Core Architecture: AI-Agent Driven

The application is built around a central orchestrator, the **`AndroidAgent`**, which is responsible for all decision-making. This design ensures that the application's logic is flexible and can be controlled or modified by an AI in the future.

-   **`AndroidAgent.kt`**: A singleton that acts as the application's 'brain'. It receives tasks from the UI (ViewModels), selects the appropriate workflow, and delegates execution to other components.
-   **`WorkflowEngine.kt`**: Interprets JSON-based workflow files provided by the agent to guide the user through business processes step-by-step.
-   **UI (Jetpack Compose)**: The UI layer is reactive. It observes state from ViewModels, which in turn receive commands and data from the `AndroidAgent`.

## 2. Connectivity Strategy: Local-First, Global-Fallback

To ensure maximum reliability, the `ScanApiService.kt` implements a robust fallback mechanism as required by the server contract.

1.  **Primary Target (Local):** All network requests first target the `LOCAL_SERVER_URL` configured in the app's settings.
2.  **Automatic Fallback (Global):** If a connection to the local server fails (timeout, network error), the service **automatically** re-issues the request to the `GLOBAL_SERVER_URL` at the `/api/proxy/` endpoint.
3.  **User Experience:** This process is seamless to the user. An error is only displayed if both the local and global servers are unreachable.

## 3. Offline-First Capability & Synchronization

The application is designed to be fully functional without a network connection.

-   **Local Database (`AppDatabase.kt` with Room):** All data, including scans, item information, and workflows, is stored locally in a SQLite database managed by AndroidX Room. This provides an instant user experience and offline capabilities.
-   **`WarehouseRepository.kt`**: This repository serves as the single source of truth for data. It first attempts to fulfill data requests from the local Room cache. If data is not available, it fetches it from the network via `ScanApiService` and updates the cache.
-   **`SyncWorker.kt` (WorkManager):** Actions performed while offline (e.g., scans, image uploads) are saved as jobs in a `SyncQueue` table in the local database. A `WorkManager` background task reliably executes these jobs once network connectivity is restored, ensuring no data is lost.

## 4. Modular & Extensible Scanner Architecture

To support various hardware vendors, the scanner integration is fully modular.

-   **`ScannerDriver.kt` Interface:** A common interface defines the essential functions for any hardware scanner (`initialize`, `startScan`, `isSupported`, etc.).
-   **Implementations:**
    -   `XCScannerDriver.kt`: The driver for `movfast` devices, implementing the `ScannerDriver` interface.
    -   **Future Drivers:** To support a new device (e.g., from Seuic or iData), a developer simply needs to create a new class that implements the `ScannerDriver` interface.
-   **`ScannerDriverFactory.kt`**: At application startup, this factory detects the device's hardware and selects the appropriate driver. This makes the system plug-and-play for different scanners.
-   **Camera & ML Kit Fallback:** If no specialized hardware driver is found, the factory defaults to a camera-based scanner using Google's ML Kit, ensuring the app works on any standard Android device.

## 5. API Contract Adherence

This application strictly adheres to the endpoints, payload formats, and architectural principles defined in the `ANDROID_INTEGRATION.md` document of the `eckwms` server repository.
