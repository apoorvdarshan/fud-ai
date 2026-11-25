import CryptoKit
import Foundation
import LiteRTLM
import Observation

@MainActor
@Observable
final class Gemma4LocalModelManager {
    struct ChatTurn: Sendable {
        enum Role: Sendable {
            case user
            case assistant
        }

        let role: Role
        let text: String
    }

    enum InstallState: Equatable {
        case notDownloaded
        case downloaded
        case downloading(Double)
        case verifying
        case preparing
        case ready
        case generating
        case failed(String)

        var isBusy: Bool {
            switch self {
            case .downloading, .verifying, .preparing, .generating:
                true
            case .notDownloaded, .downloaded, .ready, .failed:
                false
            }
        }
    }

    enum LocalModelError: LocalizedError {
        case unsupportedDevice
        case unsupportedE4BDevice
        case notDownloaded
        case busy
        case insufficientStorage(required: Int64, available: Int64)
        case storageCapacityUnavailable
        case invalidDownload(String)
        case emptyResponse
        case unusableResponse
        case inferenceFailed

        var errorDescription: String? {
            switch self {
            case .unsupportedDevice:
                LocalModelStrings.text(
                    "gemma.error.unsupportedDevice",
                    defaultValue: "Gemma 4 requires an iPhone with at least 8 GB of memory."
                )
            case .unsupportedE4BDevice:
                String(localized: "Gemma 4 E4B requires a device with at least 12 GB of RAM.")
            case .notDownloaded:
                LocalModelStrings.text(
                    "gemma.error.notDownloaded",
                    defaultValue: "Gemma 4 is not downloaded. Download it in Settings → AI Providers."
                )
            case .busy:
                LocalModelStrings.text(
                    "gemma.error.busy",
                    defaultValue: "Gemma 4 is already busy. Please wait for the current operation to finish."
                )
            case .insufficientStorage(let required, let available):
                LocalModelStrings.format(
                    "gemma.error.insufficientStorage",
                    defaultValue: "Gemma 4 needs %@ free, including installation headroom. This iPhone currently has %@ available.",
                    Self.fileSize(required),
                    Self.fileSize(available)
                )
            case .storageCapacityUnavailable:
                LocalModelStrings.text(
                    "gemma.error.storageCapacityUnavailable",
                    defaultValue: "Available storage could not be checked, so the large model download was not started."
                )
            case .invalidDownload(let reason):
                LocalModelStrings.format(
                    "gemma.error.invalidDownload",
                    defaultValue: "The Gemma 4 download could not be verified (%@). Please try again.",
                    reason
                )
            case .emptyResponse:
                LocalModelStrings.text(
                    "gemma.error.emptyResponse",
                    defaultValue: "Gemma 4 returned an empty response. Please try again."
                )
            case .unusableResponse:
                "Gemma 4: \(AIErrorKind.invalidResponse.message)"
            case .inferenceFailed:
                "Gemma 4: \(AIErrorKind.generic.message)"
            }
        }

        private static func fileSize(_ bytes: Int64) -> String {
            ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        }
    }

    struct ArtifactVerification: Equatable, Sendable {
        let byteCount: Int64
        let sha256: String
    }

    enum Variant: Sendable {
        case e2b, e4b

        var directory: String { self == .e2b ? "Gemma4E2B" : "Gemma4E4B" }
        var name: String { self == .e2b ? "Gemma 4 E2B" : "Gemma 4 E4B" }
        var minimumRAM: UInt64 { self == .e2b ? 8 : 12 }
        var modelID: String { self == .e2b ? Gemma4LocalModelManager.modelID : "gemma-4-E4B-it" }
        var filename: String { "\(modelID).litertlm" }
        var byteCount: Int64 { self == .e2b ? Gemma4LocalModelManager.artifactByteCount : 3_659_530_240 }
        var sha256: String {
            self == .e2b ? Gemma4LocalModelManager.artifactSHA256
                : "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        }
        var marker: String { "LiteRTLM-0.16.0-\(sha256)" }
        var sourceURL: URL {
            self == .e2b ? Gemma4LocalModelManager.sourceURL
                : URL(string: "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0")!
        }
        var downloadURL: URL {
            self == .e2b ? Gemma4LocalModelManager.artifactURL
                : URL(string: "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm")!
        }
    }

    static let shared = Gemma4LocalModelManager()
    static let e4b = Gemma4LocalModelManager(variant: .e4b)

    static func manager(for provider: AIProvider) -> Gemma4LocalModelManager {
        provider == .gemma4E4BLocal ? e4b : shared
    }

    nonisolated static let minimumMemoryClassGB: UInt64 = 8
    nonisolated static let minimumPhysicalMemoryBytes: UInt64 = 8 * 1_024 * 1_024 * 1_024
    nonisolated static let maxContextTokens = 4_096
    nonisolated static let visionThreadCount = 4
    nonisolated static let artifactByteCount: Int64 = 2_588_147_712
    nonisolated static let installationHeadroomBytes: Int64 = 1_024 * 1_024 * 1_024
    nonisolated static let artifactSHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    nonisolated static let artifactFilename = "gemma-4-E2B-it.litertlm"
    nonisolated static let modelID = "gemma-4-E2B-it"
    nonisolated static let preparedMarkerContents = "LiteRTLM-0.16.0-\(artifactSHA256)"
    nonisolated static let sourceRevision = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94"
    nonisolated static let sourceURL = URL(
        string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94"
    )!
    nonisolated static let licenseURL = URL(string: "https://ai.google.dev/gemma/apache_2")!
    nonisolated static let artifactURL = URL(
        string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm"
    )!

    static var isCurrentDeviceEligible: Bool {
        isEligible(physicalMemoryBytes: ProcessInfo.processInfo.physicalMemory)
    }

    static var isCurrentDeviceSelectable: Bool { isCurrentDeviceSelectable(.e2b) }

    static func isCurrentDeviceSelectable(_ variant: Variant) -> Bool {
        guard isEligible(physicalMemoryBytes: ProcessInfo.processInfo.physicalMemory, variant: variant) else { return false }
        let fileManager = FileManager.default
        let root = defaultRootDirectory(fileManager: fileManager, variant: variant)
        return hasPreparedInstall(
            modelURL: root.appendingPathComponent(variant.filename),
            verificationMarkerURL: root.appendingPathComponent("verified.sha256"),
            preparedMarkerURL: root.appendingPathComponent("prepared.version"),
            expectedByteCount: variant.byteCount,
            expectedSHA256: variant.sha256,
            expectedPreparedMarker: variant.marker,
            fileManager: fileManager
        )
    }

    private(set) var state: InstallState = .notDownloaded
    private(set) var installedByteCount: Int64 = 0
    let variant: Variant

    @ObservationIgnored private let fileManager: FileManager
    @ObservationIgnored private let rootDirectory: URL
    @ObservationIgnored private let physicalMemoryBytes: UInt64
    @ObservationIgnored private var engine: Engine?
    @ObservationIgnored private var activeDownload: GemmaArtifactDownloadOperation?
    @ObservationIgnored private var retryWait: Task<Void, Error>?

    init(
        variant: Variant = .e2b,
        rootDirectory: URL? = nil,
        physicalMemoryBytes: UInt64 = ProcessInfo.processInfo.physicalMemory,
        fileManager: FileManager = .default
    ) {
        self.variant = variant
        self.fileManager = fileManager
        self.rootDirectory = rootDirectory ?? Self.defaultRootDirectory(fileManager: fileManager, variant: variant)
        self.physicalMemoryBytes = physicalMemoryBytes
        refresh()
    }

    var isEligible: Bool {
        Self.isEligible(physicalMemoryBytes: physicalMemoryBytes, variant: variant)
    }

    var isDownloaded: Bool {
        Self.hasVerifiedInstall(
            modelURL: modelURL,
            verificationMarkerURL: verificationMarkerURL,
            expectedByteCount: variant.byteCount,
            expectedSHA256: variant.sha256,
            fileManager: fileManager
        )
    }

    var isSelectable: Bool {
        isEligible && Self.hasPreparedInstall(
            modelURL: modelURL,
            verificationMarkerURL: verificationMarkerURL,
            preparedMarkerURL: preparedMarkerURL,
            expectedByteCount: variant.byteCount,
            expectedSHA256: variant.sha256,
            expectedPreparedMarker: variant.marker,
            fileManager: fileManager
        )
    }

    var hasStoredData: Bool {
        installedByteCount > 0 || fileManager.fileExists(atPath: rootDirectory.path)
    }

    /// Folder holding the model and any partial download, for Settings › Storage to measure.
    var storageDirectory: URL { rootDirectory }

    var downloadProgress: Double? {
        guard case .downloading(let progress) = state else { return nil }
        return progress
    }

    var installedSizeDescription: String? {
        guard installedByteCount > 0 else { return nil }
        return ByteCountFormatter.string(fromByteCount: installedByteCount, countStyle: .file)
    }

    func refresh() {
        guard !state.isBusy else { return }
        installedByteCount = Self.directorySize(at: rootDirectory, fileManager: fileManager)
        state = isDownloaded ? .downloaded : .notDownloaded
    }

    func download() async {
        guard !state.isBusy else { return }
        guard isEligible else {
            state = .failed(unsupportedDeviceError.localizedDescription)
            return
        }
        guard !isDownloaded else {
            state = .downloaded
            return
        }

        do {
            try prepareRootDirectory()
            try removeUnverifiedArtifacts()
            let keptBytes = partialByteCount
            let reservation = try reserveDownloadHeadroom(keptBytes: keptBytes)
            defer { LocalModelDownloadBudget.shared.release(reservation) }

            state = .downloading(Double(keptBytes) / Double(variant.byteCount))
            // The partial file survives failures and relaunches. Every attempt asks the pinned
            // resolve URL for the remaining range, so a resume never replays an expired CDN link.
            var retry = GemmaDownloadRetryState()
            while partialByteCount < variant.byteCount {
                let download = GemmaArtifactDownloadOperation(
                    fileURL: partialModelURL,
                    expectedByteCount: variant.byteCount,
                    fileManager: fileManager
                ) { [weak self] progress in
                    Task { @MainActor [weak self] in
                        guard let self, case .downloading = self.state else { return }
                        self.state = .downloading(progress)
                    }
                }
                activeDownload = download
                do {
                    try await download.start(url: variant.downloadURL)
                    activeDownload = nil
                } catch {
                    activeDownload = nil
                    if Task.isCancelled || Self.isCancellation(error) { throw error }
                    let decision = retry.recordFailure(
                        error,
                        lastingProgressBytes: download.lastingProgressBytes
                    )
                    guard case .retry(let delay) = decision else { throw error }
                    let wait = Task { try await Task.sleep(for: .seconds(delay)) }
                    retryWait = wait
                    try await withTaskCancellationHandler {
                        try await wait.value
                    } onCancel: {
                        wait.cancel()
                    }
                    retryWait = nil
                }
            }

            state = .verifying
            let partialURL = partialModelURL
            let verification = try await Task.detached(priority: .utility) {
                try Self.verifyArtifact(at: partialURL)
            }.value
            guard verification.byteCount == variant.byteCount else {
                throw LocalModelError.invalidDownload(
                    LocalModelStrings.format(
                        "gemma.verification.wrongSize",
                        defaultValue: "expected %@ bytes, received %@",
                        String(variant.byteCount),
                        String(verification.byteCount)
                    )
                )
            }
            guard verification.sha256 == variant.sha256 else {
                throw LocalModelError.invalidDownload(LocalModelStrings.text(
                    "gemma.verification.hashMismatch",
                    defaultValue: "SHA-256 mismatch"
                ))
            }

            try fileManager.moveItem(at: partialModelURL, to: modelURL)
            try Data(variant.sha256.utf8).write(to: verificationMarkerURL, options: .atomic)
            installedByteCount = Self.directorySize(at: rootDirectory, fileManager: fileManager)

            _ = try await ensureEngine()
            state = .ready
        } catch {
            activeDownload = nil
            retryWait = nil
            let cancelled = Task.isCancelled || Self.isCancellation(error)
            // A failed download keeps its partial so the next Download resumes it (Delete still
            // removes it). Cancel and bytes that failed verification discard it.
            if cancelled || Self.isVerificationFailure(error) {
                try? fileManager.removeItem(at: partialModelURL)
            }
            if !isDownloaded, partialByteCount == 0,
               fileManager.fileExists(atPath: rootDirectory.path) {
                try? fileManager.removeItem(at: rootDirectory)
            }
            installedByteCount = Self.directorySize(at: rootDirectory, fileManager: fileManager)
            if cancelled {
                state = isDownloaded ? .downloaded : .notDownloaded
            } else {
                state = .failed(Self.errorMessage(for: error))
            }
        }
    }

    func prepare() async {
        guard !state.isBusy else { return }
        do {
            _ = try await ensureEngine()
            state = .ready
        } catch {
            state = .failed(Self.errorMessage(for: error))
        }
    }

    func cancelDownload() {
        guard case .downloading = state else { return }
        activeDownload?.cancel()
        retryWait?.cancel()
    }

    func delete() throws {
        guard !state.isBusy else { throw LocalModelError.busy }
        activeDownload?.cancel()
        activeDownload = nil
        engine = nil
        if fileManager.fileExists(atPath: rootDirectory.path) {
            try fileManager.removeItem(at: rootDirectory)
        }
        installedByteCount = 0
        state = .notDownloaded
    }

    func generate(
        prompt: String,
        images: [Data] = [],
        systemPrompt: String? = nil,
        history: [ChatTurn] = [],
        maxOutputTokens: Int = 1_024
    ) async throws -> String {
        guard !state.isBusy else { throw LocalModelError.busy }
        guard isEligible else { throw unsupportedDeviceError }
        guard isDownloaded else { throw LocalModelError.notDownloaded }

        let loadedEngine: Engine
        do {
            loadedEngine = try await ensureEngine()
        } catch {
            state = .failed(Self.errorMessage(for: error))
            throw error
        }

        state = .generating
        do {
            let initialMessages = history.suffix(8).map { turn in
                Message(turn.text, role: turn.role == .user ? .user : .model)
            }
            let conversationConfig = ConversationConfig(
                systemMessage: systemPrompt.map { Message($0) },
                initialMessages: initialMessages,
                thinkingConfig: ThinkingConfig(enableThinking: false)
            )
            let conversation = try await loadedEngine.createConversation(with: conversationConfig)
            let contents = images.map(Content.imageData) + [.text(prompt)]
            let response = try await conversation.sendMessage(
                Message(contents: contents),
                maxOutputTokens: max(1, min(maxOutputTokens, 4_096)),
                thinkingConfig: ThinkingConfig(enableThinking: false)
            )
            let text = response.toString.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { throw LocalModelError.emptyResponse }
            state = .ready
            return text
        } catch {
            state = .failed(Self.errorMessage(for: error))
            throw error
        }
    }

    nonisolated static func isEligible(physicalMemoryBytes: UInt64, variant: Variant = .e2b) -> Bool {
        memoryClassGB(physicalMemoryBytes: physicalMemoryBytes) >= variant.minimumRAM
    }

    /// iOS can report slightly less memory than the phone's marketed RAM because
    /// part of physical memory is reserved by the system. Round the reported GiB
    /// upward so an 8 GB device remains in the 8 GB eligibility class.
    nonisolated static func memoryClassGB(physicalMemoryBytes: UInt64) -> UInt64 {
        let wholeGiB = physicalMemoryBytes / (1_024 * 1_024 * 1_024)
        let hasPartialGiB = physicalMemoryBytes % (1_024 * 1_024 * 1_024) != 0
        return wholeGiB + (hasPartialGiB ? 1 : 0)
    }

    /// Bytes kept from an interrupted download already occupy their share of the space, so only
    /// the remainder plus installation headroom has to be free.
    nonisolated static func hasRequiredStorage(
        availableBytes: Int64,
        keptBytes: Int64 = 0,
        artifactBytes: Int64 = artifactByteCount,
        headroomBytes: Int64 = installationHeadroomBytes
    ) -> Bool {
        guard artifactBytes >= 0, headroomBytes >= 0,
              artifactBytes <= Int64.max - headroomBytes else {
            return false
        }
        return availableBytes >= requiredStorageBytes(
            keptBytes: keptBytes,
            artifactBytes: artifactBytes,
            headroomBytes: headroomBytes
        )
    }

    nonisolated static func requiredStorageBytes(
        keptBytes: Int64 = 0,
        artifactBytes: Int64 = artifactByteCount,
        headroomBytes: Int64 = installationHeadroomBytes
    ) -> Int64 {
        artifactBytes - min(max(keptBytes, 0), artifactBytes) + headroomBytes
    }

    nonisolated static func engineConfig(
        modelPath: String,
        backend: Backend,
        cacheDir: String
    ) throws -> EngineConfig {
        try EngineConfig(
            modelPath: modelPath,
            backend: backend,
            // Gemma 4's vision encoder currently fails Metal preparation on iOS
            // (STABLEHLO_COMPOSITE). Keep text generation on the requested backend,
            // but compile the vision tower with XNNPACK.
            visionBackend: .cpu(threadCount: visionThreadCount),
            maxNumTokens: maxContextTokens,
            cacheDir: cacheDir
        )
    }

    nonisolated static func hasVerifiedInstall(
        modelURL: URL,
        verificationMarkerURL: URL,
        expectedByteCount: Int64,
        expectedSHA256: String,
        fileManager: FileManager
    ) -> Bool {
        guard let attributes = try? fileManager.attributesOfItem(atPath: modelURL.path),
              let byteCount = (attributes[.size] as? NSNumber)?.int64Value,
              byteCount == expectedByteCount,
              let markerData = try? Data(contentsOf: verificationMarkerURL),
              let marker = String(data: markerData, encoding: .utf8) else {
            return false
        }
        return marker.trimmingCharacters(in: .whitespacesAndNewlines) == expectedSHA256
    }

    nonisolated static func hasPreparedInstall(
        modelURL: URL,
        verificationMarkerURL: URL,
        preparedMarkerURL: URL,
        expectedByteCount: Int64,
        expectedSHA256: String,
        expectedPreparedMarker: String = preparedMarkerContents,
        fileManager: FileManager
    ) -> Bool {
        guard hasVerifiedInstall(
            modelURL: modelURL,
            verificationMarkerURL: verificationMarkerURL,
            expectedByteCount: expectedByteCount,
            expectedSHA256: expectedSHA256,
            fileManager: fileManager
        ), let markerData = try? Data(contentsOf: preparedMarkerURL),
           let marker = String(data: markerData, encoding: .utf8) else {
            return false
        }
        return marker.trimmingCharacters(in: .whitespacesAndNewlines) == expectedPreparedMarker
    }

    nonisolated static func verifyArtifact(
        at fileURL: URL,
        chunkSize: Int = 4 * 1_024 * 1_024
    ) throws -> ArtifactVerification {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        var hasher = SHA256()
        var byteCount: Int64 = 0
        while true {
            try Task.checkCancellation()
            guard let data = try handle.read(upToCount: chunkSize), !data.isEmpty else { break }
            byteCount += Int64(data.count)
            hasher.update(data: data)
        }

        let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return ArtifactVerification(byteCount: byteCount, sha256: digest)
    }

    nonisolated static func directorySize(at directory: URL, fileManager: FileManager) -> Int64 {
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileAllocatedSizeKey, .fileSizeKey]
        guard let enumerator = fileManager.enumerator(
            at: directory,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        ) else {
            return 0
        }

        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            guard let values = try? fileURL.resourceValues(forKeys: keys),
                  values.isRegularFile == true else {
                continue
            }
            total += Int64(values.fileAllocatedSize ?? values.fileSize ?? 0)
        }
        return total
    }

    private var modelURL: URL {
        rootDirectory.appendingPathComponent(variant.filename)
    }

    /// Named after the pinned artifact hash so a partial never continues a different artifact.
    private var partialModelURL: URL {
        rootDirectory.appendingPathComponent("\(variant.sha256).partial")
    }

    /// Bytes an interrupted download left for the next attempt to resume after.
    private var partialByteCount: Int64 {
        guard let attributes = try? fileManager.attributesOfItem(atPath: partialModelURL.path) else {
            return 0
        }
        return (attributes[.size] as? NSNumber)?.int64Value ?? 0
    }

    private var verificationMarkerURL: URL {
        rootDirectory.appendingPathComponent("verified.sha256")
    }

    private var preparedMarkerURL: URL {
        rootDirectory.appendingPathComponent("prepared.version")
    }

    private var cacheDirectory: URL {
        rootDirectory.appendingPathComponent("Cache", isDirectory: true)
    }

    private static func defaultRootDirectory(fileManager: FileManager, variant: Variant) -> URL {
        let applicationSupport = (try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fileManager.temporaryDirectory

        return applicationSupport
            .appendingPathComponent("FudAI", isDirectory: true)
            .appendingPathComponent("LocalModels", isDirectory: true)
            .appendingPathComponent(variant.directory, isDirectory: true)
    }

    private func prepareRootDirectory() throws {
        try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableRoot = rootDirectory
        try mutableRoot.setResourceValues(values)
    }

    private func removeUnverifiedArtifacts() throws {
        for url in [modelURL, verificationMarkerURL, preparedMarkerURL]
        where fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
        // A partial of another artifact revision, or one longer than the artifact, can never
        // verify. The current partial is kept so this download resumes it.
        if partialByteCount > variant.byteCount {
            try fileManager.removeItem(at: partialModelURL)
        }
        let contents = (try? fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
        for url in contents
        where url.pathExtension == "partial" && url.lastPathComponent != partialModelURL.lastPathComponent {
            try fileManager.removeItem(at: url)
        }
    }

    private func reserveDownloadHeadroom(keptBytes: Int64) throws -> UUID {
        let values = try rootDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        guard let available = values.volumeAvailableCapacityForImportantUsage else {
            throw LocalModelError.storageCapacityUnavailable
        }
        let required = Self.requiredStorageBytes(keptBytes: keptBytes, artifactBytes: variant.byteCount)
        let budget = LocalModelDownloadBudget.shared
        guard let reservation = budget.reserve(requiredBytes: required, availableBytes: available) else {
            throw LocalModelError.insufficientStorage(
                required: required, available: budget.unreservedBytes(availableBytes: available)
            )
        }
        return reservation
    }

    private var unsupportedDeviceError: LocalModelError {
        variant == .e2b ? .unsupportedDevice : .unsupportedE4BDevice
    }

    private func ensureEngine() async throws -> Engine {
        guard isEligible else { throw unsupportedDeviceError }
        guard isDownloaded else { throw LocalModelError.notDownloaded }
        if let engine { return engine }

        let other = variant == .e2b ? Self.e4b : Self.shared
        guard other.state != .generating && other.state != .preparing else { throw LocalModelError.busy }
        other.engine = nil
        if other.state == .ready { other.refresh() }
        state = .preparing
        do {
            let loadedEngine: Engine
            let gpuCache = cacheDirectory.appendingPathComponent("GPUText-CPUVision", isDirectory: true)
            try fileManager.createDirectory(at: gpuCache, withIntermediateDirectories: true)
            let gpuConfig = try Self.engineConfig(
                modelPath: modelURL.path,
                backend: .gpu,
                cacheDir: gpuCache.path
            )
            let gpuEngine = Engine(engineConfig: gpuConfig)
            do {
                try await gpuEngine.initialize()
                loadedEngine = gpuEngine
            } catch {
                // LiteRT-LM's documented multimodal path prefers Metal on iOS. Some
                // devices/runtime combinations reject GPU engine creation, so retry
                // once with an isolated XNNPACK cache before surfacing the error.
                let cpuCache = cacheDirectory.appendingPathComponent("CPU", isDirectory: true)
                try fileManager.createDirectory(at: cpuCache, withIntermediateDirectories: true)
                let cpuConfig = try Self.engineConfig(
                    modelPath: modelURL.path,
                    backend: .cpu(),
                    cacheDir: cpuCache.path
                )
                let cpuEngine = Engine(engineConfig: cpuConfig)
                try await cpuEngine.initialize()
                loadedEngine = cpuEngine
            }

            try Data(variant.marker.utf8).write(to: preparedMarkerURL, options: .atomic)
            engine = loadedEngine
            state = .ready
            return loadedEngine
        } catch {
            try? fileManager.removeItem(at: preparedMarkerURL)
            throw error
        }
    }

    private static func errorMessage(for error: Error) -> String {
        if let localized = error as? LocalizedError,
           let description = localized.errorDescription {
            return description
        }
        return error.localizedDescription
    }

    private static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private static func isVerificationFailure(_ error: Error) -> Bool {
        guard case .invalidDownload? = error as? LocalModelError else { return false }
        return true
    }
}

/// Bounded retry bookkeeping for the Gemma download. Every attempt resumes after the bytes already
/// on disk; only consecutive attempts that left the partial no longer than they found it spend the
/// retry budget. Each reset needs the partial to grow, so the retries always end.
struct GemmaDownloadRetryState {
    enum Decision: Equatable {
        case retry(after: TimeInterval)
        case fail
    }

    static let standardDelays: [TimeInterval] = [1, 2, 4, 8, 16, 30]

    private(set) var consecutiveFailures = 0
    let delays: [TimeInterval]

    init(delays: [TimeInterval] = standardDelays) {
        self.delays = delays
    }

    /// `lastingProgressBytes` is how much longer the attempt left the partial. A restart from zero
    /// that drops before passing the old length received bytes but made no progress.
    mutating func recordFailure(_ error: Error, lastingProgressBytes: Int64) -> Decision {
        if lastingProgressBytes > 0 { consecutiveFailures = 0 }
        guard Self.isTransient(error), consecutiveFailures < delays.count else { return .fail }
        let delay = delays[consecutiveFailures]
        consecutiveFailures += 1
        return .retry(after: delay)
    }

    static func isTransient(_ error: Error) -> Bool {
        if let downloadError = error as? GemmaArtifactDownloadOperation.DownloadError {
            switch downloadError {
            case .httpStatus(let status):
                return status == 408 || status == 429 || (500...599).contains(status)
            case .unexpectedRange:
                // The partial was reset, so the next attempt starts over from zero.
                return true
            case .invalidHTTPResponse:
                return false
            }
        }
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .secureConnectionFailed, .cannotLoadFromNetwork,
             .backgroundSessionWasDisconnected, .callIsActive, .dataNotAllowed:
            return true
        default:
            return false
        }
    }
}

/// One attempt at the Gemma artifact: appends whatever follows the bytes already in `fileURL`,
/// requested with an HTTP Range. The bytes it writes stay on disk whatever the outcome.
final class GemmaArtifactDownloadOperation: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    enum DownloadError: LocalizedError {
        case invalidHTTPResponse
        case httpStatus(Int)
        case unexpectedRange

        var errorDescription: String? {
            switch self {
            case .invalidHTTPResponse, .unexpectedRange:
                LocalModelStrings.text(
                    "gemma.download.invalidHTTPResponse",
                    defaultValue: "The model server returned an invalid response."
                )
            case .httpStatus(let status):
                LocalModelStrings.format(
                    "gemma.download.httpStatus",
                    defaultValue: "The model server returned HTTP %@.",
                    String(status)
                )
            }
        }
    }

    private static let progressStepBytes: Int64 = 1_024 * 1_024

    private let fileURL: URL
    private let expectedByteCount: Int64
    private let fileManager: FileManager
    private let configuration: URLSessionConfiguration
    private let progressHandler: @Sendable (Double) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var output: FileHandle?
    private var failure: Error?
    private var isCancelled = false
    private var fileLength: Int64 = 0
    private var reportedLength: Int64 = 0
    private var startLength: Int64 = 0

    init(
        fileURL: URL,
        expectedByteCount: Int64,
        fileManager: FileManager,
        configuration: URLSessionConfiguration = GemmaArtifactDownloadOperation.sessionConfiguration(),
        progressHandler: @escaping @Sendable (Double) -> Void
    ) {
        self.fileURL = fileURL
        self.expectedByteCount = expectedByteCount
        self.fileManager = fileManager
        self.configuration = configuration
        self.progressHandler = progressHandler
    }

    static func sessionConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 120
        configuration.timeoutIntervalForResource = 6 * 60 * 60
        configuration.waitsForConnectivity = true
        // The body streams straight to the partial file; none of it belongs in a cache.
        configuration.urlCache = nil
        return configuration
    }

    /// How much longer this attempt left the partial than it found it. A server that ignores the
    /// Range header replaces the partial, so an attempt can receive bytes and still end below zero.
    var lastingProgressBytes: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return fileLength - startLength
    }

    /// Returns once `fileURL` holds `expectedByteCount` bytes. A transfer that ends early throws and
    /// leaves what it received for the next attempt to resume after.
    func start(url: URL) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let output: FileHandle
                let keptLength: Int64
                do {
                    if !fileManager.fileExists(atPath: fileURL.path),
                       !fileManager.createFile(atPath: fileURL.path, contents: nil) {
                        throw CocoaError(.fileWriteUnknown, userInfo: [NSURLErrorKey: fileURL])
                    }
                    output = try FileHandle(forWritingTo: fileURL)
                    keptLength = Int64(try output.seekToEnd())
                } catch {
                    continuation.resume(throwing: error)
                    return
                }

                var request = URLRequest(url: url)
                // Range offsets must count stored bytes, never a compressed encoding of them.
                request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
                if keptLength > 0 {
                    request.setValue("bytes=\(keptLength)-", forHTTPHeaderField: "Range")
                }

                let delegateQueue = OperationQueue()
                delegateQueue.name = "app.fud-ai.gemma-download"
                delegateQueue.maxConcurrentOperationCount = 1

                let session = URLSession(
                    configuration: configuration,
                    delegate: self,
                    delegateQueue: delegateQueue
                )
                let task = session.dataTask(with: request)

                lock.lock()
                self.continuation = continuation
                self.session = session
                self.task = task
                self.output = output
                fileLength = keptLength
                reportedLength = keptLength
                startLength = keptLength
                let cancelledBeforeStart = isCancelled
                lock.unlock()

                reportProgress(keptLength)
                task.resume()
                if Task.isCancelled || cancelledBeforeStart { cancel() }
            }
        } onCancel: {
            self.cancel()
        }
    }

    func cancel() {
        lock.lock()
        isCancelled = true
        let task = task
        lock.unlock()
        task?.cancel()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        do {
            try accept(response)
            completionHandler(.allow)
        } catch {
            fail(with: error)
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let output = output
        let keptLength = fileLength
        let failed = failure != nil
        lock.unlock()
        guard let output, !failed else { return }

        let length = keptLength + Int64(data.count)
        do {
            guard length <= expectedByteCount else {
                throw Self.sizeMismatch(received: length, expected: expectedByteCount)
            }
            try output.write(contentsOf: data)
        } catch {
            fail(with: error)
            dataTask.cancel()
            return
        }

        lock.lock()
        fileLength = length
        let report = length - reportedLength >= Self.progressStepBytes || length == expectedByteCount
        if report { reportedLength = length }
        lock.unlock()
        if report { reportProgress(length) }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        lock.lock()
        let output = output
        let failure = failure
        let cancelled = isCancelled
        let finished = fileLength == expectedByteCount
        self.output = nil
        lock.unlock()

        var result: Result<Void, Error>
        if cancelled {
            result = .failure(URLError(.cancelled))
        } else if let failure {
            result = .failure(failure)
        } else if let error {
            result = .failure(error)
        } else if !finished {
            // The body ended before the artifact did; the next attempt resumes from here.
            result = .failure(URLError(.networkConnectionLost))
        } else {
            result = .success(())
        }
        if let output {
            do {
                try output.synchronize()
            } catch {
                if case .success = result { result = .failure(error) }
            }
            try? output.close()
        }
        complete(result)
    }

    /// A 206 is appended only when it starts exactly where the partial ends.
    nonisolated static func continues(
        at offset: Int64,
        contentRange: String?,
        expectedByteCount: Int64
    ) -> Bool {
        guard let value = contentRange?.trimmingCharacters(in: .whitespaces),
              value.hasPrefix("bytes ") else {
            return false
        }
        let parts = value.dropFirst("bytes ".count)
            .split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let bounds = parts[0].split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]) else {
            return false
        }
        return start == offset && start <= end && end < expectedByteCount
            && (parts[1] == "*" || Int64(parts[1]) == expectedByteCount)
    }

    private func accept(_ response: URLResponse) throws {
        guard let response = response as? HTTPURLResponse else {
            throw DownloadError.invalidHTTPResponse
        }
        lock.lock()
        let keptLength = fileLength
        lock.unlock()
        switch response.statusCode {
        case 206:
            guard Self.continues(
                at: keptLength,
                contentRange: response.value(forHTTPHeaderField: "Content-Range"),
                expectedByteCount: expectedByteCount
            ) else {
                try truncate(to: 0)
                throw DownloadError.unexpectedRange
            }
        case 200:
            // The server ignored the Range header, so the body starts at byte zero.
            if keptLength > 0 { try truncate(to: 0) }
            let declaredLength = response.expectedContentLength
            if declaredLength >= 0, declaredLength != expectedByteCount {
                throw Self.sizeMismatch(received: declaredLength, expected: expectedByteCount)
            }
        case 416:
            // Only reachable with a partial shorter than the artifact, which the server no longer
            // accepts as a resume point.
            try truncate(to: 0)
            throw DownloadError.unexpectedRange
        default:
            throw DownloadError.httpStatus(response.statusCode)
        }
    }

    private func truncate(to length: Int64) throws {
        lock.lock()
        let output = output
        lock.unlock()
        try output?.truncate(atOffset: UInt64(length))
        lock.lock()
        fileLength = length
        reportedLength = length
        lock.unlock()
        reportProgress(length)
    }

    private func fail(with error: Error) {
        lock.lock()
        if failure == nil { failure = error }
        lock.unlock()
    }

    /// Progress counts the kept bytes against the full artifact.
    private func reportProgress(_ length: Int64) {
        guard expectedByteCount > 0 else { return }
        progressHandler(min(1, max(0, Double(length) / Double(expectedByteCount))))
    }

    private static func sizeMismatch(received: Int64, expected: Int64) -> Error {
        Gemma4LocalModelManager.LocalModelError.invalidDownload(
            LocalModelStrings.format(
                "gemma.verification.wrongSize",
                defaultValue: "expected %@ bytes, received %@",
                String(expected),
                String(received)
            )
        )
    }

    private func complete(_ result: Result<Void, Error>) {
        lock.lock()
        let continuation = continuation
        let session = session
        self.continuation = nil
        self.session = nil
        task = nil
        lock.unlock()

        guard let continuation else { return }
        session?.finishTasksAndInvalidate()
        continuation.resume(with: result)
    }
}
