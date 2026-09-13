import PhotosUI
import SwiftUI
import UIKit

/// "Coach" tab — a persistent AI conversation that has access to the user's profile,
/// weight history, food log, computed forecast, and workout diary. Handles multi-turn
/// chat with memory, a reset button, and prompt chips.
struct ChatView: View {
    @Environment(ChatStore.self) private var chatStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(BodyMeasurementStore.self) private var bodyMeasurementStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"

    @State private var draft = ""
    @State private var attachedImage: UIImage?
    @State private var capturedImage: UIImage?
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var showHostedQuotaPaywall = false
    @State private var showHostedPaywall = false
    @State private var showHostedCredits = false
    @State private var showResetConfirmation = false
    @State private var showCamera = false
    @State private var showPhotoPicker = false
    @State private var voice = CoachVoiceRecorder()
    @State private var voicePressStart: Date?
    @State private var voicePulse = false
    @FocusState private var isInputFocused: Bool
    @ScaledMetric(relativeTo: .title2) private var coachHeroSize = 92.0
    @ScaledMetric(relativeTo: .title2) private var coachHeroIconSize = 38.0
    @ScaledMetric(relativeTo: .body) private var composerControlSize = 40.0

    private var userProfile: UserProfile { profileStore.profile }
    private var messages: [ChatMessage] { chatStore.messages }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Group {
                    if messages.isEmpty {
                        emptyState
                    } else {
                        messageList
                    }
                }
                .contentShape(Rectangle())
                .simultaneousGesture(
                    TapGesture().onEnded { isInputFocused = false }
                )

                if !messages.isEmpty {
                    promptChips
                }

                inputArea
            }
            .background(AppColors.appBackground)
            .navigationTitle("Coach")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if !messages.isEmpty { showResetConfirmation = true }
                    } label: {
                        Image(systemName: "arrow.counterclockwise")
                            .foregroundStyle(messages.isEmpty ? Color.secondary : AppColors.calorie)
                    }
                    .disabled(messages.isEmpty)
                    .accessibilityLabel("Reset Chat")
                }
            }
            .alert("Reset Chat", isPresented: $showResetConfirmation) {
                Button("Cancel", role: .cancel) { }
                Button("Reset", role: .destructive) {
                    chatStore.reset()
                    errorMessage = nil
                }
            } message: {
                Text("Clear all messages and start fresh? This can't be undone.")
            }
            .sheet(isPresented: $showHostedQuotaPaywall) {
                HostedQuotaSoftPaywall(
                    onBuyCreditsOrUpgrade: {
                        if RevenueCatManager.shared.hasHostedEntitlement {
                            showHostedCredits = true
                        } else {
                            showHostedPaywall = true
                        }
                    },
                    onSwitchBYOK: { errorMessage = nil }
                )
            }
            .sheet(isPresented: $showHostedPaywall) {
                HostedPaywallView()
            }
            .sheet(isPresented: $showHostedCredits) {
                HostedCreditsSheet()
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraView(image: $capturedImage)
                    .ignoresSafeArea()
            }
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
            .onChange(of: capturedImage) { _, newValue in
                guard let image = newValue else { return }
                capturedImage = nil
                attachedImage = image
                errorMessage = nil
            }
            .onChange(of: selectedPhotoItem) { _, newValue in
                guard let item = newValue else { return }
                selectedPhotoItem = nil
                Task {
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self),
                              let image = UIImage(data: data) else {
                            await MainActor.run { errorMessage = "Could not load that photo." }
                            return
                        }
                        await MainActor.run {
                            attachedImage = image
                            errorMessage = nil
                        }
                    } catch {
                        await MainActor.run {
                            errorMessage = "Could not load that photo."
                        }
                    }
                }
            }
        }
    }

    // MARK: - Sections

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer()
                    .frame(height: 54)
                ZStack {
                    Circle()
                        .fill(.ultraThinMaterial)
                        .frame(width: coachHeroSize, height: coachHeroSize)
                        .overlay(
                            Circle().stroke(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.35), Color.white.opacity(0.05)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.8
                            )
                        )
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .font(.system(size: coachHeroIconSize, weight: .medium))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                }
                .accessibilityHidden(true)
                .padding(.bottom, 18)
                VStack(spacing: 8) {
                    Text("Ask your Coach")
                        .font(.system(.title2, design: .rounded, weight: .semibold))
                    Text("Your coach can see your nutrition, goals, and workout diary. Ask about food, progress, recovery, or your training plan.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(2)
                        .padding(.horizontal, 24)
                }
                .accessibilityElement(children: .combine)

                emptyPromptGrid
                    .padding(.top, 26)
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    ForEach(messages) { msg in
                        MessageBubble(message: msg)
                            .id(msg.id)
                    }
                    if isSending {
                        HStack(alignment: .top, spacing: 8) {
                            CoachAssistantBadge()

                            TypingIndicator()
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                                .background(.ultraThinMaterial, in: UnevenRoundedRectangle(
                                    cornerRadii: .init(topLeading: 8, bottomLeading: 18, bottomTrailing: 18, topTrailing: 18),
                                    style: .continuous
                                ))
                                .overlay(
                                    UnevenRoundedRectangle(
                                        cornerRadii: .init(topLeading: 8, bottomLeading: 18, bottomTrailing: 18, topTrailing: 18),
                                        style: .continuous
                                    )
                                        .stroke(Color.white.opacity(0.15), lineWidth: 0.5)
                                )
                            Spacer(minLength: 48)
                        }
                        .padding(.horizontal)
                        .id("typing")
                    }
                    if let err = errorMessage {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .accessibilityHidden(true)
                            Text(err)
                        }
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .foregroundStyle(.red)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(Color.red.opacity(0.25), lineWidth: 0.6)
                        )
                        .padding(.horizontal)
                    }
                }
                .padding(.top, 14)
                .padding(.bottom, 16)
            }
            .scrollDismissesKeyboard(.immediately)
            .onAppear {
                guard let lastID = messages.last?.id else { return }
                DispatchQueue.main.async {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
            .onChange(of: messages.count) { _, _ in
                withAnimation { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
            }
            .onChange(of: isSending) { _, sending in
                if sending { withAnimation { proxy.scrollTo("typing", anchor: .bottom) } }
            }
            .onChange(of: isInputFocused) { _, focused in
                guard focused, let lastID = messages.last?.id else { return }
                // Animate alongside the keyboard for responsiveness.
                withAnimation(.easeOut(duration: 0.25)) {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                // Fires *after* the keyboard is fully shown — by now the ScrollView's
                // safe-area inset is definitely applied, so this re-anchor catches the
                // case where the initial scroll ran against the pre-keyboard viewport
                // (bubble was hidden until the user typed and forced a re-layout).
                guard isInputFocused, let lastID = messages.last?.id else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
        }
    }

    private var suggestedPrompts: [String] {
        var values: [String]
        switch userProfile.goal {
        case .lose:
            values = [
                "What's my expected weight in 30 days?",
                "How do I lose weight faster safely?",
                "Am I eating too much?",
                "What should I eat for dinner?",
            ]
        case .gain:
            values = [
                "What's my expected weight in 30 days?",
                "How do I gain weight healthily?",
                "Am I eating enough?",
                "High-protein foods I can add?",
            ]
        case .maintain:
            values = [
                "Am I holding my weight?",
                "What's my average intake?",
                "Macro suggestions?",
                "How's my trend?",
            ]
        }
        if !strengthWorkoutStore.completedSessions.isEmpty {
            values.insert("Analyze my last 4 weeks of training", at: 0)
        }
        return values
    }

    private var emptyPromptGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(Array(suggestedPrompts.prefix(4)), id: \.self) { prompt in
                Button {
                    draft = prompt
                    send()
                } label: {
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(AppColors.calorie)
                            .padding(.top, 2)
                            .accessibilityHidden(true)
                        Text(prompt)
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                            .lineLimit(3)
                        Spacer(minLength: 0)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 70, alignment: .topLeading)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(AppColors.calorie.opacity(0.045))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(AppColors.calorie.opacity(0.18), lineWidth: 0.6)
                    )
                }
                .buttonStyle(.plain)
                .disabled(isSending)
            }
        }
        .padding(.horizontal, 16)
    }

    /// Context-aware suggested prompts — pick a different set based on goal to keep them relevant.
    private var promptChips: some View {

        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 32, height: 44)
                    .background(AppColors.calorie.opacity(0.08), in: Circle())
                    .accessibilityHidden(true)

                ForEach(suggestedPrompts, id: \.self) { chip in
                    Button {
                        draft = chip
                        send()
                    } label: {
                        Text(chip)
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(AppColors.calorie)
                            .background(
                                Capsule().fill(.ultraThinMaterial)
                            )
                            .overlay(
                                Capsule()
                                    .fill(AppColors.calorie.opacity(0.10))
                            )
                            .overlay(
                                Capsule()
                                    .stroke(
                                        LinearGradient(
                                            colors: [AppColors.calorie.opacity(0.35), AppColors.calorie.opacity(0.10)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                        ),
                                        lineWidth: 0.6
                                    )
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(isSending)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
    }

    private var inputArea: some View {
        VStack(spacing: 8) {
            if let attachedImage {
                attachmentPreview(attachedImage)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            inputBar
        }
        .animation(.easeInOut(duration: 0.18), value: attachedImage == nil)
        .onChange(of: voice.submittedTranscript) { _, newValue in
            guard let text = newValue, !text.isEmpty else { return }
            draft = text
            send()
            voice.submittedTranscript = nil
        }
    }

    private func attachmentPreview(_ image: UIImage) -> some View {
        HStack(spacing: 10) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 62, height: 62)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.18), lineWidth: 0.6)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text("Image attached")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text("Send with your Coach message")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                attachedImage = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(AppColors.calorie.opacity(0.18), lineWidth: 0.7)
        )
        .padding(.horizontal, 12)
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            // Left region: attach + text field, or the live recording indicator.
            Group {
                if voice.phase == .idle {
                    HStack(spacing: 8) {
                        attachMenu
                        TextField("Ask Coach…", text: $draft, axis: .vertical)
                            .font(.system(.body, design: .rounded))
                            .lineLimit(1...5)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 12)
                            .focused($isInputFocused)
                    }
                } else {
                    recordingIndicator
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Trailing control (kept as the stable last child).
            trailingControl
        }
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [Color.white.opacity(0.25), Color.white.opacity(0.05)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 0.8
                )
        )
        .shadow(color: Color.black.opacity(0.18), radius: 14, x: 0, y: 6)
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .padding(.top, 4)
    }

    private var attachMenu: some View {
        Menu {
            Button { openCamera() } label: { Label("Camera", systemImage: "camera.fill") }
            Button { showPhotoPicker = true } label: { Label("Photo Library", systemImage: "photo.on.rectangle") }
        } label: {
            Image(systemName: attachedImage == nil ? "plus.circle.fill" : "photo.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: composerControlSize, height: composerControlSize)
        }
        .disabled(isSending)
        .padding(.leading, 6)
    }

    @ViewBuilder private var trailingControl: some View {
        switch voice.phase {
        case .locked:
            HStack(spacing: 8) {
                voiceCancelButton
                voiceSendButton
            }
            .padding(.trailing, 5)
        case .transcribing:
            ProgressView()
                .frame(width: composerControlSize, height: composerControlSize)
                .padding(.trailing, 5)
        case .idle where canSend:
            sendButton
                .padding(.trailing, 5)
                .animation(.easeInOut(duration: 0.15), value: canSend)
        default: // idle-empty or holding — keep the mic mounted through the press
            micButton
                .padding(.trailing, 5)
        }
    }

    private var sendButton: some View {
        Button {
            send()
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(
                    canSend
                        ? AnyShapeStyle(LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                        : AnyShapeStyle(Color.secondary.opacity(0.35)),
                    in: Circle()
                )
                .overlay(Circle().stroke(Color.white.opacity(canSend ? 0.25 : 0.10), lineWidth: 0.6))
                .shadow(color: canSend ? AppColors.calorie.opacity(0.35) : .clear, radius: 8, x: 0, y: 4)
        }
        .disabled(!canSend)
    }

    private var micButton: some View {
        let holding = voice.phase == .holding
        return Image(systemName: "mic.fill")
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(holding ? .white : AppColors.calorie)
            .frame(width: composerControlSize, height: composerControlSize)
            .background(
                holding ? AnyShapeStyle(Color.red) : AnyShapeStyle(AppColors.calorie.opacity(0.14)),
                in: Circle()
            )
            .scaleEffect(holding ? 1.25 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: holding)
            .contentShape(Circle())
            .gesture(micGesture)
            .id("coachMic")
    }

    private var micGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if voice.phase == .idle {
                    isInputFocused = false
                    voicePressStart = Date()
                    voice.begin()
                }
                voice.updateDrag(value.translation.width, threshold: 90)
            }
            .onEnded { value in
                let held = voicePressStart.map { Date().timeIntervalSince($0) } ?? 0
                if value.translation.width < -90 {
                    voice.cancel()
                } else if held < 0.35 && abs(value.translation.width) < 24 {
                    voice.lock()
                } else {
                    voice.stopAndSend()
                }
                voicePressStart = nil
            }
    }

    private var voiceSendButton: some View {
        Button {
            voice.stopAndSend()
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: Circle()
                )
                .shadow(color: AppColors.calorie.opacity(0.35), radius: 8, x: 0, y: 4)
        }
    }

    private var voiceCancelButton: some View {
        Button {
            voice.cancel()
        } label: {
            Image(systemName: "trash")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.red)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(Color.secondary.opacity(0.14), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private var recordingIndicator: some View {
        HStack(spacing: 8) {
            if voice.phase == .transcribing {
                ProgressView().controlSize(.small)
                Text("Transcribing…")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                Circle()
                    .fill(Color.red)
                    .frame(width: 9, height: 9)
                    .opacity(voicePulse ? 0.3 : 1.0)
                    .onAppear {
                        withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                            voicePulse = true
                        }
                    }
                    .onDisappear { voicePulse = false }
                Text(formatVoiceElapsed(voice.elapsed))
                    .font(.system(.callout, design: .rounded, weight: .medium))
                    .monospacedDigit()
                Text(voiceHint)
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(voice.cancelArmed ? .red : .secondary)
                    .lineLimit(1)
            }
        }
        .padding(.leading, 14)
        .padding(.vertical, 12)
    }

    private var voiceHint: String {
        if voice.phase == .holding {
            return voice.cancelArmed ? "Release to cancel" : "‹ slide to cancel"
        }
        return voice.liveText.isEmpty ? "Listening…" : voice.liveText
    }

    private var canSend: Bool {
        !isSending && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || attachedImage != nil)
    }

    // MARK: - Send

    private func send() {
        let typedText = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let image = attachedImage
        guard (!typedText.isEmpty || image != nil), !isSending else { return }

        let text = typedText.isEmpty ? "Analyze this image." : typedText
        let imageDataForAI = image.flatMap {
            resizedJPEGData(from: $0, maxDimension: 1600, compressionQuality: 0.78)
        }
        let thumbnailData = image.flatMap {
            resizedJPEGData(from: $0, maxDimension: 700, compressionQuality: 0.68)
        }
        if image != nil, imageDataForAI == nil {
            errorMessage = "Failed to process the image."
            return
        }

        chatStore.append(ChatMessage(role: .user, content: text, attachmentImageData: thumbnailData))
        draft = ""
        attachedImage = nil
        errorMessage = nil
        isSending = true
        let historyForCall = chatStore.contextMessages().dropLast()  // exclude the user msg we just appended

        Task {
            defer { isSending = false }
            do {
                let reply = try await ChatService.sendMessage(
                    history: Array(historyForCall),
                    newUserMessage: text,
                    imageData: imageDataForAI,
                    profile: userProfile,
                    weights: weightStore.entries,
                    bodyFats: bodyFatStore.entries,
                    measurements: bodyMeasurementStore.entries,
                    foods: foodStore.entries,
                    fastingSessions: fastingStore.sessions,
                    heightMetric: heightUnitRaw == "cm",
                    weightMetric: weightUnitRaw == "kg",
                    workoutSessions: strengthWorkoutStore.completedSessions,
                    workoutPlans: Array(strengthWorkoutStore.dayPlans.values),
                    workoutPreferences: strengthWorkoutStore.preferences,
                    workoutAccessEnabled: true
                )
                chatStore.append(ChatMessage(role: .assistant, content: reply))
            } catch {
                if let quotaError = error as? HostedAIQuotaError {
                    switch quotaError {
                    case .quotaExceeded:
                        showHostedQuotaPaywall = true
                    case .noActiveSubscription:
                        showHostedPaywall = true
                    case .notHostedMode, .rateLimited:
                        errorMessage = quotaError.errorDescription
                    }
                } else {
                    errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                }
            }
        }
    }

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            errorMessage = "Camera is not available on this device."
            return
        }
        showCamera = true
    }

    private func resizedJPEGData(from image: UIImage, maxDimension: CGFloat, compressionQuality: CGFloat) -> Data? {
        let originalSize = image.size
        let longestSide = max(originalSize.width, originalSize.height)
        guard longestSide > 0 else {
            return image.jpegData(compressionQuality: compressionQuality)
        }

        let scale = min(1, maxDimension / longestSide)
        let targetSize = CGSize(width: originalSize.width * scale, height: originalSize.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return resized.jpegData(compressionQuality: compressionQuality)
    }
}

// MARK: - Supporting views

/// Lightweight Markdown renderer for assistant chat bubbles — handles the formatting the Coach
/// actually emits: #/##/### headings, "- / * / 1." lists, ``` code fences ```, `inline code`,
/// **bold**, *italic*, and [links](url). Block layout is done here; inline styling uses
/// AttributedString's inline-only markdown so no third-party dependency is needed.
private struct MarkdownMessageText: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(MarkdownMessageBlockCache.blocks(for: text)) { block in
                switch block.kind {
                case .heading(let level):
                    Text(inline(block.text))
                        .font(.system(headingStyle(level), design: .rounded, weight: .bold))
                case .bullet:
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("•").font(.system(.body, design: .rounded))
                        Text(inline(block.text)).font(.system(.body, design: .rounded))
                    }
                case .numbered(let number):
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text("\(number).").font(.system(.body, design: .rounded, weight: .medium))
                        Text(inline(block.text)).font(.system(.body, design: .rounded))
                    }
                case .code:
                    Text(block.text)
                        .font(.system(.callout, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                case .paragraph:
                    Text(inline(block.text)).font(.system(.body, design: .rounded))
                }
            }
        }
    }

    private func headingStyle(_ level: Int) -> Font.TextStyle {
        switch level {
        case 1: return .title3
        case 2: return .headline
        default: return .subheadline
        }
    }

    private func inline(_ string: String) -> AttributedString {
        (try? AttributedString(markdown: string, options: .init(
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible
        ))) ?? AttributedString(string)
    }

    struct Block: Identifiable, Equatable {
        enum Kind: Equatable { case heading(Int), bullet, numbered(String), code, paragraph }
        let id: String
        let kind: Kind
        let text: String
    }

    fileprivate static func parse(_ raw: String) -> [Block] {
        var blocks: [Block] = []
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var index = 0
        var blockIndex = 0
        while index < lines.count {
            let trimmed = lines[index].trimmingCharacters(in: .whitespaces)

            if trimmed.hasPrefix("```") {
                var codeLines: [String] = []
                index += 1
                while index < lines.count, !lines[index].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    codeLines.append(lines[index])
                    index += 1
                }
                index += 1 // skip the closing fence
                let text = codeLines.joined(separator: "\n")
                blocks.append(Block(id: "code-\(blockIndex)", kind: .code, text: text))
                blockIndex += 1
                continue
            }

            if trimmed.isEmpty { index += 1; continue }

            if let level = headingLevel(trimmed) {
                let content = String(trimmed.drop(while: { $0 == "#" })).trimmingCharacters(in: .whitespaces)
                blocks.append(Block(id: "heading-\(blockIndex)", kind: .heading(level), text: content))
            } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("+ ") {
                blocks.append(Block(id: "bullet-\(blockIndex)", kind: .bullet, text: String(trimmed.dropFirst(2)).trimmingCharacters(in: .whitespaces)))
            } else if let (number, rest) = numberedItem(trimmed) {
                blocks.append(Block(id: "numbered-\(blockIndex)", kind: .numbered(number), text: rest))
            } else {
                blocks.append(Block(id: "paragraph-\(blockIndex)", kind: .paragraph, text: trimmed))
            }
            blockIndex += 1
            index += 1
        }
        return blocks
    }

    private static func headingLevel(_ string: String) -> Int? {
        let hashes = string.prefix(while: { $0 == "#" }).count
        guard hashes >= 1, hashes <= 3, string.dropFirst(hashes).first == " " else { return nil }
        return hashes
    }

    private static func numberedItem(_ string: String) -> (String, String)? {
        guard let dotIndex = string.firstIndex(of: ".") else { return nil }
        let numberPart = string[string.startIndex..<dotIndex]
        guard !numberPart.isEmpty, numberPart.allSatisfy(\.isNumber),
              string[string.index(after: dotIndex)...].first == " " else { return nil }
        let rest = String(string[string.index(after: dotIndex)...]).trimmingCharacters(in: .whitespaces)
        return (String(numberPart), rest)
    }
}

private enum MarkdownMessageBlockCache {
    private static let cache: NSCache<NSString, NSArray> = {
        let cache = NSCache<NSString, NSArray>()
        cache.countLimit = 48
        return cache
    }()

    static func blocks(for text: String) -> [MarkdownMessageText.Block] {
        let key = text as NSString
        if let cached = cache.object(forKey: key) as? [MarkdownMessageText.Block] {
            return cached
        }
        let parsed = MarkdownMessageText.parse(text)
        cache.setObject(parsed as NSArray, forKey: key)
        return parsed
    }
}

private struct MessageBubble: View {
    let message: ChatMessage

    private var isUser: Bool { message.role == .user }
    private var bubbleShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            cornerRadii: .init(
                topLeading: isUser ? 20 : 8,
                bottomLeading: 20,
                bottomTrailing: 20,
                topTrailing: isUser ? 8 : 20
            ),
            style: .continuous
        )
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if !isUser {
                CoachAssistantBadge()
            } else {
                Spacer(minLength: 48)
            }

            bubble

            if isUser {
                // no trailing icon
            } else {
                Spacer(minLength: 48)
            }
        }
        .padding(.horizontal)
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 9) {
            if let imageData = message.attachmentImageData,
               let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 196, height: 140)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Color.white.opacity(isUser ? 0.25 : 0.12), lineWidth: 0.7)
                    )
            }

            if isUser {
                // User's own typed text — show verbatim, no markdown.
                Text(message.content)
                    .font(.system(.body, design: .rounded))
                    .textSelection(.enabled)
                    .foregroundStyle(.white)
            } else {
                // Coach replies often use markdown — render it.
                MarkdownMessageText(text: message.content)
                    .textSelection(.enabled)
                    .foregroundStyle(.primary)
            }
        }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(bubbleBackground)
            .overlay(bubbleStroke)
            .overlay(alignment: .top) {
                if isUser { bubbleHighlight }
            }
            .clipShape(bubbleShape)
            .shadow(color: isUser ? AppColors.calorie.opacity(0.18) : Color.black.opacity(0.08),
                    radius: isUser ? 9 : 5, x: 0, y: isUser ? 5 : 3)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if isUser {
            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            ZStack {
                bubbleShape
                    .fill(.ultraThinMaterial)
                bubbleShape
                    .fill(AppColors.calorie.opacity(0.035))
            }
        }
    }

    private var bubbleStroke: some View {
        bubbleShape
            .stroke(
                LinearGradient(
                    colors: isUser
                        ? [Color.white.opacity(0.45), Color.white.opacity(0.05)]
                        : [Color.white.opacity(0.22), Color.white.opacity(0.04)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                lineWidth: 0.7
            )
    }

    /// Glassy top highlight on user bubbles — makes the gradient read as polished glass, not flat paint.
    private var bubbleHighlight: some View {
        LinearGradient(
            colors: [Color.white.opacity(0.35), Color.white.opacity(0)],
            startPoint: .top,
            endPoint: .center
        )
        .blendMode(.plusLighter)
        .allowsHitTesting(false)
    }
}

private struct CoachAssistantBadge: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(.ultraThinMaterial)
                .frame(width: 28, height: 28)
                .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 0.5))
            Image(systemName: "sparkles")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                )
        }
        .padding(.top, 8)
        .accessibilityHidden(true)
    }
}

private struct TypingIndicator: View {
    @State private var phase = 0
    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .frame(width: 7, height: 7)
                    .opacity(phase == i ? 1 : 0.3)
                    .scaleEffect(phase == i ? 1.15 : 1.0)
                    .animation(.easeInOut(duration: 0.35), value: phase)
            }
        }
        .onAppear {
            Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { _ in
                phase = (phase + 1) % 3
            }
        }
    }
}
