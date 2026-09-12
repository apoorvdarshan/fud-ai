import Foundation

/// Routes a recorded audio file to the selected batch STT provider and returns the transcription.
/// Native iOS transcription uses SFSpeechRecognizer directly in VoiceInputView (streaming);
/// Whisper Base runs the full recording locally, while remote providers upload it.
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

    /// Transcribe an audio file using the selected batch provider, with an optional
    /// independently configured remote STT fallback.
    static func transcribe(audioURL: URL) async throws -> String {
        if AIModeSettings.isHosted {
            guard let audioData = try? Data(contentsOf: audioURL) else {
                throw SpeechError.fileReadFailed
            }
            let language = SpeechSettings.selectedLanguage(for: .deepgram).apiLanguageCode
            return try await MainActor.run {
                try await AIGate.runWithHostedQuota(.hostedSTT) {
                    try await HostedAIService.transcribe(
                        audioData: audioData,
                        mimeType: mimeType(for: audioURL),
                        language: language
                    )
                }
            }
        }

        let provider: SpeechProvider = SpeechSettings.selectedProvider
        guard let audioData = try? Data(contentsOf: audioURL) else {
            throw SpeechError.fileReadFailed
        }

        do {
            return try await transcribe(audioURL: audioURL, audioData: audioData, provider: provider)
        } catch {
            if error is CancellationError { throw error }
            guard SpeechSettings.fallbackEnabled else { throw error }
            let fallback = SpeechSettings.selectedFallbackProvider
            guard fallback != provider,
                  !fallback.requiresAPIKey || SpeechSettings.apiKey(for: fallback)?.isEmpty == false else {
                throw error
            }
            return try await transcribe(audioURL: audioURL, audioData: audioData, provider: fallback)
        }
    }

    private static func transcribe(audioURL: URL, audioData: Data, provider: SpeechProvider) async throws -> String {
        let selectedLanguage = SpeechSettings.selectedLanguage(for: provider)
        let languageCode = selectedLanguage.apiLanguageCode
        if provider == .whisperBase {
            return try await WhisperBaseModelManager.shared.transcribe(
                audioURL: audioURL,
                languageCode: languageCode
            )
        }
        guard provider.requiresAPIKey else {
            throw SpeechError.apiError("Native iOS transcription is handled in-view, not via SpeechService.")
        }
        let apiKey = SpeechSettings.apiKey(for: provider)
        if apiKey == nil || apiKey?.isEmpty == true {
            throw SpeechError.noAPIKey
        }
        let resolvedAPIKey = apiKey ?? ""
        switch provider {
        case .nativeIOS:
            throw SpeechError.apiError("Native iOS transcription is handled in-view.")
        case .whisperBase:
            throw SpeechError.apiError("Whisper Base could not be initialized.")
        case .gemini:
            return try await callGeminiAudio(
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: apiKey,
                languageCode: languageCode
            )
        case .openai:
            return try await callOpenAIWhisper(
                baseURL: "https://api.openai.com/v1",
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: resolvedAPIKey,
                languageCode: languageCode
            )
        case .groq:
            return try await callOpenAIWhisper(
                baseURL: "https://api.groq.com/openai/v1",
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: resolvedAPIKey,
                languageCode: languageCode
            )
        case .mistral:
            return try await callOpenAIWhisper(
                baseURL: "https://api.mistral.ai/v1",
                model: provider.defaultModel,
                audioData: audioData,
                apiKey: resolvedAPIKey,
                languageCode: languageCode,
                responseFormat: nil
            )
        case .deepgram:
            return try await callDeepgram(model: provider.defaultModel, audioData: audioData, apiKey: resolvedAPIKey, languageCode: languageCode)
        case .assemblyai:
            return try await callAssemblyAI(
                speechModels: [provider.defaultModel, "universal-2"],
                audioData: audioData,
                apiKey: resolvedAPIKey,
                languageCode: languageCode
            )
        }
    }

    // MARK: - Gemini Audio

    private struct GeminiUploadedFile {
        let uri: String
        let name: String
    }

    /// Gemini 3.5 Transcribe uses the Files API followed by the Interactions API.
    /// The older general-purpose Gemini audio route used generateContent and cannot
    /// be updated safely by changing only the model identifier.
    private static func callGeminiAudio(model: String, audioData: Data, apiKey: String?, languageCode: String?) async throws -> String {
        guard let apiKey else { throw SpeechError.noAPIKey }
        let uploadedFile = try await uploadGeminiAudio(audioData: audioData, apiKey: apiKey)

        do {
            guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/interactions") else {
                throw SpeechError.apiError("Invalid Gemini interactions URL.")
            }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(apiKey, forHTTPHeaderField: "X-goog-api-key")
            request.httpBody = try JSONSerialization.data(withJSONObject: geminiInteractionBody(
                model: model,
                fileURI: uploadedFile.uri,
                mimeType: "audio/m4a",
                languageCode: languageCode
            ))

            let (responseData, response) = try await send(request)
            guard let http = response as? HTTPURLResponse else { throw SpeechError.invalidResponse }
            guard (200..<300).contains(http.statusCode) else {
                throw SpeechError.apiError(decodeErrorMessage(responseData) ?? "HTTP \(http.statusCode)")
            }
            guard let transcript = geminiTranscript(from: responseData) else {
                throw SpeechError.invalidResponse
            }

            await deleteGeminiFile(name: uploadedFile.name, apiKey: apiKey)
            return transcript
        } catch {
            await deleteGeminiFile(name: uploadedFile.name, apiKey: apiKey)
            throw error
        }
    }

    static func geminiInteractionBody(
        model: String,
        fileURI: String,
        mimeType: String,
        languageCode: String?
    ) -> [String: Any] {
        let languageCodes = languageCode.map { [$0] } ?? []
        return [
            "model": model,
            "input": [
                [
                    "type": "audio",
                    "uri": fileURI,
                    "mime_type": mimeType,
                ]
            ],
            "generation_config": [
                "transcription_config": [
                    "language_codes": languageCodes,
                    "mode": ["type": "smart"],
                ]
            ],
        ]
    }

    static func geminiTranscript(from data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        let outputText = (json["output_text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let outputText, !outputText.isEmpty { return outputText }

        if let outputs = json["outputs"] as? [[String: Any]],
           let text = outputs.compactMap({ $0["text"] as? String }).first(where: { !$0.isEmpty }) {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if let steps = json["steps"] as? [[String: Any]] {
            for step in steps {
                guard let content = step["content"] as? [[String: Any]] else { continue }
                if let text = content.compactMap({ $0["text"] as? String }).first(where: { !$0.isEmpty }) {
                    return text.trimmingCharacters(in: .whitespacesAndNewlines)
                }
            }
        }
        return nil
    }

    private static func uploadGeminiAudio(audioData: Data, apiKey: String) async throws -> GeminiUploadedFile {
        guard let startURL = URL(string: "https://generativelanguage.googleapis.com/upload/v1beta/files") else {
            throw SpeechError.apiError("Invalid Gemini upload URL.")
        }
        var startRequest = URLRequest(url: startURL)
        startRequest.httpMethod = "POST"
        startRequest.setValue(apiKey, forHTTPHeaderField: "X-goog-api-key")
        startRequest.setValue("resumable", forHTTPHeaderField: "X-Goog-Upload-Protocol")
        startRequest.setValue("start", forHTTPHeaderField: "X-Goog-Upload-Command")
        startRequest.setValue(String(audioData.count), forHTTPHeaderField: "X-Goog-Upload-Header-Content-Length")
        startRequest.setValue("audio/m4a", forHTTPHeaderField: "X-Goog-Upload-Header-Content-Type")
        startRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        startRequest.httpBody = try JSONSerialization.data(withJSONObject: [
            "file": ["display_name": "fud-ai-speech.m4a"]
        ])

        let (startData, startResponse) = try await send(startRequest)
        guard let startHTTP = startResponse as? HTTPURLResponse,
              (200..<300).contains(startHTTP.statusCode),
              let uploadURLString = startHTTP.value(forHTTPHeaderField: "X-Goog-Upload-URL"),
              let uploadURL = URL(string: uploadURLString)
        else {
            throw SpeechError.apiError(decodeErrorMessage(startData) ?? "Gemini upload could not be started.")
        }

        var uploadRequest = URLRequest(url: uploadURL)
        uploadRequest.httpMethod = "POST"
        uploadRequest.setValue(String(audioData.count), forHTTPHeaderField: "Content-Length")
        uploadRequest.setValue("0", forHTTPHeaderField: "X-Goog-Upload-Offset")
        uploadRequest.setValue("upload, finalize", forHTTPHeaderField: "X-Goog-Upload-Command")
        uploadRequest.setValue("audio/m4a", forHTTPHeaderField: "Content-Type")
        uploadRequest.httpBody = audioData

        let (uploadData, uploadResponse) = try await send(uploadRequest)
        guard let uploadHTTP = uploadResponse as? HTTPURLResponse,
              (200..<300).contains(uploadHTTP.statusCode),
              let json = try? JSONSerialization.jsonObject(with: uploadData) as? [String: Any],
              let file = json["file"] as? [String: Any],
              let uri = file["uri"] as? String,
              let name = file["name"] as? String
        else {
            throw SpeechError.apiError(decodeErrorMessage(uploadData) ?? "Gemini audio upload failed.")
        }
        return GeminiUploadedFile(uri: uri, name: name)
    }

    private static func deleteGeminiFile(name: String, apiKey: String) async {
        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/\(name)") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue(apiKey, forHTTPHeaderField: "X-goog-api-key")
        _ = try? await send(request)
    }

    // MARK: - OpenAI-compatible (OpenAI + Groq + Mistral)

    /// OpenAI's /v1/audio/transcriptions spec. Groq implements the same endpoint on their host.
    private static func callOpenAIWhisper(
        baseURL: String,
        model: String,
        audioData: Data,
        apiKey: String,
        languageCode: String?,
        responseFormat: String? = "text"
    ) async throws -> String {
        guard let url = URL(string: "\(baseURL)/audio/transcriptions") else {
            throw SpeechError.apiError("Invalid URL.")
        }
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var fields = ["model": model]
        if let responseFormat {
            fields["response_format"] = responseFormat
        }
        if let languageCode {
            fields["language"] = languageCode
        }
        request.httpBody = multipartBody(boundary: boundary, fields: fields, file: (fieldName: "file", filename: "audio.m4a", mimeType: "audio/m4a", data: audioData))

        let (data, response) = try await send(request)
        guard let http = response as? HTTPURLResponse else { throw SpeechError.invalidResponse }
        if http.statusCode != 200 {
            throw SpeechError.apiError(decodeErrorMessage(data) ?? "HTTP \(http.statusCode)")
        }
        if responseFormat == nil,
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let text = json["text"] as? String,
           !text.isEmpty {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        // response_format=text returns plain text, not JSON.
        if let text = String(data: data, encoding: .utf8), !text.isEmpty {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        throw SpeechError.invalidResponse
    }

    // MARK: - Deepgram

    private static func callDeepgram(model: String, audioData: Data, apiKey: String, languageCode: String?) async throws -> String {
        // Deepgram pre-recorded endpoint — no multipart, raw audio body.
        var components = URLComponents(string: "https://api.deepgram.com/v1/listen")
        var queryItems = [
            URLQueryItem(name: "model", value: model),
            URLQueryItem(name: "smart_format", value: "true"),
            URLQueryItem(name: "punctuate", value: "true")
        ]
        if let languageCode {
            queryItems.append(URLQueryItem(name: "language", value: languageCode))
        }
        components?.queryItems = queryItems

        guard let url = components?.url else {
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

    static func assemblyAITranscriptBody(
        audioURL: String,
        speechModels: [String],
        languageCode: String?
    ) -> [String: Any] {
        var body: [String: Any] = [
            "audio_url": audioURL,
            "speech_models": speechModels,
        ]
        if let languageCode {
            body["language_code"] = languageCode
        } else {
            body["language_detection"] = true
        }
        return body
    }

    private static func callAssemblyAI(
        speechModels: [String],
        audioData: Data,
        apiKey: String,
        languageCode: String?
    ) async throws -> String {
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
        let submitBody = assemblyAITranscriptBody(
            audioURL: audioRef,
            speechModels: speechModels,
            languageCode: languageCode
        )
        submitReq.httpBody = try JSONSerialization.data(withJSONObject: submitBody)
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

    private static func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m4a": return "audio/mp4"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "caf": return "audio/x-caf"
        default: return "audio/wav"
        }
    }
}
