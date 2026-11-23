# LinaChat

**LinaChat** is a desktop messaging application developed using Java and JavaFX. It provides a real-time communication experience with a modern graphical interface.

## Features

- Real-time messaging between multiple users.
- User profile creation and contact management.
- Image sharing with direct image display.
- File attachments and audio messages.
- Responsive and user-friendly JavaFX interface.

## Technologies

- **Language:** Java 21
- **GUI:** JavaFX 21, FXML, CSS
- **UI Design:** Scene Builder
- **Communication:** TCP Sockets
- **Concurrency:** Multithreading and ExecutorService
- **Build Tool:** Maven
- **Version Control:** Git and GitHub

## Architecture

LinaChat follows a Client-Server architecture:

- **Server:** Manages client connections and message distribution.
- **Client:** Provides the graphical interface and communication features.
- **Communication:** TCP sockets enable real-time communication.

## Requirements

- JDK 21
- Maven
- IntelliJ IDEA
- JavaFX 21

## Getting Started

1. Clone the repository.
2. Open the project in IntelliJ IDEA.
3. Configure JDK 21.
4. Load the Maven dependencies.
5. Start the server.
6. Launch the client application.

## Project Status

Under development.

## Assistant Gemini

The server reads `GEMINI_API_KEY` from the Windows environment with
`System.getenv("GEMINI_API_KEY")`. Do not add the key to Java source or FXML.

The optional `GEMINI_MODEL` variable selects the model; the default is
`gemini-3.8-flash`. Optional variables are `GEMINI_TIMEOUT_SECONDS` and
`AI_MAX_HISTORY_MESSAGES`.

The fallback model is `gemini-3.5-flash-lite` and can be changed with
`GEMINI_FALLBACK_MODEL`. HTTP 503 responses trigger at most two retries with
1-second and 2-second backoff. The primary model and fallback remain on Google
Gemini's free-tier-compatible API; this project never enables billing.

## Author

Developed as an academic Java application project.
