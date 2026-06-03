# Repository Atlas: Vidremover

## Project Responsibility
Vidremover is an Android application designed for media management (videos and images) on the device. It utilizes modern Android architecture including Jetpack Compose for UI, Hilt for dependency injection, Coroutines for asynchronous operations, and MVVM + Clean Architecture principles.

## System Entry Points
- `app/src/main/AndroidManifest.xml`: Application manifest defining entry points and permissions (e.g., storage access).
- `app/src/main/java/com/vidremover/VidRemoverApp.kt`: The main `Application` class, annotated with `@HiltAndroidApp` for DI initialization.
- `app/build.gradle.kts`: Module-level build configuration defining dependencies and Android setup.

## Directory Map (Aggregated)
| Directory | Responsibility Summary |
|-----------|------------------------|
| `app/src/main/java/com/vidremover/data/` | Data Layer: Implements `MediaStoreDataSource` for accessing device media, DTOs, and repositories. |
| `app/src/main/java/com/vidremover/di/` | Dependency Injection: Hilt modules providing singletons and repository bindings. |
| `app/src/main/java/com/vidremover/domain/` | Domain Layer: Contains Core business logic, domain models, and UseCases for media retrieval and deletion. |
| `app/src/main/java/com/vidremover/presentation/` | UI Layer: Contains ViewModels for state management, Jetpack Compose screens, and theming. |
| `app/src/main/res/` | Resources: XML drawables, values, and mipmap icons. |
