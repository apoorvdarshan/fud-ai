import Foundation

/// Routes a recorded audio file to the selected STT provider and returns the transcription.
/// Native iOS transcription uses SFSpeechRecognizer directly in VoiceInputView (streaming);
/// this service is only for the remote providers that accept a full audio upload.
struct SpeechService {
    enum SpeechError: LocalizedError {
        case noAPIKey
        case fileReadFailed
        case networkError(Error)
        case apiError(String)
        case invalidResponse

        var errorDescription: String? {
            switch self {
            case .noAPIKey:
                return "No API key configured for this speech provider. Add one in Settings → Speech-to-Text."
            case .fileReadFailed:
                return "Could not read the recorded audio file."
            case .networkError(let err):
                return "Network error: \(err.localizedDescription)"
            case .apiError(let msg):
                return "Speech API error: \(msg)"
            case .invalidResponse:
                return "Unexpected response from the speech provider."
            }
        }
    }

    /// Transcribe an audio file using the currently-selected speech provider.
    /// Caller should only invoke this for non-native providers.
    static func transcribe(audioURL: URL) async throws -> String {
        let provider = SpeechSettings.selectedProvider
        guard provider.requiresAPIKey else {
            // Native iOS handled directly by VoiceInputView.
            throw SpeechError.apiError("Native iOS transcription is handled in-view, not via SpeechService.")
        }
        guard let apiKey = SpeechSettings.apiKey(for: provider), !apiKey.isEmpty else {
            throw SpeechError.noAPIKey
        }
        guard let audioData = try? Data(contentsOf: audioURL) else {
            throw SpeechError.fileReadFailed
        }
        switch provider {
        case .nativeIOS:
            throw SpeechError.apiError("Native iOS transcription is handled in-view.")
        case .openai:
            return try await callOpenAIWhisper(
                baseURL: "https://api.openai.com/v1",
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: apiKey
            )
        case .groq:
            return try await callOpenAIWhisper(
                baseURL: "https://api.groq.com/openai/v1",
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: apiKey
            )
        case .deepgram:
            return try await callDeepgram(model: provider.defaultModel, audioData: audioData, apiKey: apiKey)
        case .assemblyai:
            return try await callAssemblyAI(audioData: audioData, apiKey: apiKey)
        }
    }

    // MARK: - OpenAI-compatible (OpenAI + Groq)

    /// OpenAI's /v1/audio/transcriptions spec. Groq implements the same endpoint on their host.
    private static func callOpenAIWhisper(baseURL: String, model: String, audioData: Data, apiKey: String) async throws -> String {
        guard let url = URL(string: "\(baseURL)/audio/transcriptions") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = multipartBody(boundary: boundary, fields: [
            "model": model,
            "response_format": "text",
        ], file: (fieldName: "file", filename: "audio.m4a", mimeType: "audio/m4a", data: audioData))

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else { throw SpeechError.invalidResponse }
        if http.statusCode != 200 {
            throw SpeechError.apiError(decodeErrorMessage(data) ?? "HTTP \(http.statusCode)")
        }
        // response_format=text returns plain text, not JSON.
        if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        throw SpeechError.invalidResponse
    }

    // MARK: - Deepgram

    private static func callDeepgram(model: String, audioData: Data, apiKey: String) async throws -> String {
        // Deepgram pre-recorded endpoint — no multipart, raw audio body.
        guard let url = URL(string: "https://api.deepgram.com/v1/listen?model=\(model)&smart_format=true&punctuate=true") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Token \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("audio/m4a", forHTTPHeaderField: "Content-Type")
        request.httpBody = audioData

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else { throw SpeechError.invalidResponse }
        if http.statusCode != 200 {
            throw SpeechError.apiError(decodeErrorMessage(data) ?? "HTTP \(http.statusCode)")
        }
        // Response: { results: { channels: [ { alternatives: [ { transcript: "..." } ] } ] } }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = json["results"] as? [String: Any],
              let channels = results["channels"] as? [[String: Any]],
              let first = channels.first,
              let alternatives = first["alternatives"] as? [[String: Any]],
              let transcript = alternatives.first?["transcript"] as? String
        else {
            throw SpeechError.invalidResponse
        }
        return transcript
    }

    // MARK: - AssemblyAI (2-step: upload then transcribe-and-poll)

    private static func callAssemblyAI(audioData: Data, apiKey: String) async throws -> String {
        // 1. Upload raw audio, get a temporary upload URL.
        guard let uploadURL = URL(string: "https://api.assemblyai.com/v2/upload") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        var uploadReq = URLRequest(url: uploadURL)
        uploadReq.httpMethod = "POST"
        uploadReq.setValue(apiKey, forHTTPHeaderField: "Authorization")
        uploadReq.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        uploadReq.httpBody = audioData
        let (uploadData, uploadResp) = try await send(uploadReq)
        guard let uploadHttp = uploadResp as? HTTPURLResponse, uploadHttp.statusCode == 200,
              let uploadJson = try? JSONSerialization.jsonObject(with: uploadData) as? [String: Any],
              let audioRef = uploadJson["upload_url"] as? String
        else {
            throw SpeechError.apiError(decodeErrorMessage(uploadData) ?? "Upload failed.")
        }

        // 2. Submit a transcript job.
        guard let submitURL = URL(string: "https://api.assemblyai.com/v2/transcript") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        var submitReq = URLRequest(url: submitURL)
        submitReq.httpMethod = "POST"
        submitReq.setValue(apiKey, forHTTPHeaderField: "Authorization")
        submitReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        submitReq.httpBody = try JSONSerialization.data(withJSONObject: ["audio_url": audioRef])
        let (submitData, submitResp) = try await send(submitReq)
        guard let submitHttp = submitResp as? HTTPURLResponse, submitHttp.statusCode == 200,
              let submitJson = try? JSONSerialization.jsonObject(with: submitData) as? [String: Any],
              let jobID = submitJson["id"] as? String
        else {
            throw SpeechError.apiError(decodeErrorMessage(submitData) ?? "Submit failed.")
        }

        // 3. Poll every 1s up to 60s until the job finishes.
        guard let pollURL = URL(string: "https://api.assemblyai.com/v2/transcript/\(jobID)") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        for _ in 0..<60 {
            var pollReq = URLRequest(url: pollURL)
            pollReq.setValue(apiKey, forHTTPHeaderField: "Authorization")
            let (pollData, _) = try await send(pollReq)
            guard let pollJson = try? JSONSerialization.jsonObject(with: pollData) as? [String: Any],
                  let status = pollJson["status"] as? String
            else { continue }
            switch status {
            case "completed":
                if let text = pollJson["text"] as? String { return text }
                throw SpeechError.invalidResponse
            case "error":
                throw SpeechError.apiError(pollJson["error"] as? String ?? "Transcription failed.")
            default:
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        throw SpeechError.apiError("Transcription timed out after 60 seconds.")
    }

    // MARK: - Helpers

    private static func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await URLSession.shared.data(for: request)
        } catch {
            throw SpeechError.networkError(error)
        }
    }

    private static func decodeErrorMessage(_ data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let err = json["error"] as? [String: Any], let msg = err["message"] as? String { return msg }
        if let msg = json["error"] as? String { return msg }
        if let msg = json["err_msg"] as? String { return msg }
        return nil
    }

    private static func multipartBody(
        boundary: String,
        fields: [String: String],
        file: (fieldName: String, filename: String, mimeType: String, data: Data)
    ) -> Data {
        var body = Data()
        let boundaryPrefix = "--\(boundary)\r\n"

        for (name, value) in fields {
            body.append(boundaryPrefix.data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }

        body.append(boundaryPrefix.data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"\(file.fieldName)\"; filename=\"\(file.filename)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(file.mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(file.data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        return body
    }
}
