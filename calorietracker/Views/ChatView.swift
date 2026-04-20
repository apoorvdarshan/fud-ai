import SwiftUI

/// "Coach" tab — a persistent AI conversation that has access to the user's profile,
/// weight history, food log, and computed forecast. Handles multi-turn chat with memory
/// (saved locally), a reset button, and context-aware quick-reply prompt chips.
struct ChatView: View {
    @Environment(ChatStore.self) private var chatStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(FoodStore.self) private var foodStore
    @AppStorage("useMetric") private var useMetric = false

    @State private var draft = ""
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var showResetConfirmation = false
    @FocusState private var isInputFocused: Bool

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

                promptChips

                inputBar
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
        }
    }

    // MARK: - Sections

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            ZStack {
                Circle()
                    .fill(.ultraThinMaterial)
                    .frame(width: 108, height: 108)
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
                    .shadow(color: AppColors.calorie.opacity(0.18), radius: 24, x: 0, y: 10)
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
            }
            Text("Ask your Coach")
                .font(.system(.title2, design: .rounded, weight: .semibold))
            Text("Your coach can see your weight history, calorie log, and goals. Ask about expected weight, what to eat, or how to hit your target.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(messages) { msg in
                        MessageBubble(message: msg)
                            .id(msg.id)
                    }
                    if isSending {
                        HStack {
                            TypingIndicator()
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                                        .stroke(Color.white.opacity(0.15), lineWidth: 0.5)
                                )
                                .padding(.leading, 4)
                            Spacer()
                        }
                        .padding(.horizontal)
                        .id("typing")
                    }
                    if let err = errorMessage {
                        Text(err)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.red)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(Color.red.opacity(0.25), lineWidth: 0.5)
                            )
                            .padding(.horizontal)
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
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
                // Delay so the scroll runs after the keyboard has changed the safe-area
                // inset — otherwise we scroll against the pre-keyboard viewport and the
                // newest message ends up hidden behind the keyboard.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    withAnimation { proxy.scrollTo(lastID, anchor: .bottom) }
                }
            }
        }
    }

    /// Context-aware suggested prompts — pick a different set based on goal to keep them relevant.
    private var promptChips: some View {
        let chips: [String] = {
            switch userProfile.goal {
            case .lose:
                return [
                    "What's my expected weight in 30 days?",
                    "How do I lose weight faster safely?",
                    "Am I eating too much?",
                    "What should I eat for dinner?",
                ]
            case .gain:
                return [
                    "What's my expected weight in 30 days?",
                    "How do I gain weight healthily?",
                    "Am I eating enough?",
                    "High-protein foods I can add?",
                ]
            case .maintain:
                return [
                    "Am I holding my weight?",
                    "What's my average intake?",
                    "Macro suggestions?",
                    "How's my trend?",
                ]
            }
        }()

        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips, id: \.self) { chip in
                    Button {
                        draft = chip
                        send()
                    } label: {
                        Text(chip)
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
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
            .padding(.vertical, 8)
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("Ask Coach…", text: $draft, axis: .vertical)
                .font(.system(.body, design: .rounded))
                .lineLimit(1...5)
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .focused($isInputFocused)

            Button {
                send()
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background(
                        canSend
                            ? AnyShapeStyle(LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                            : AnyShapeStyle(Color.secondary.opacity(0.35)),
                        in: Circle()
                    )
                    .overlay(
                        Circle().stroke(Color.white.opacity(canSend ? 0.25 : 0.10), lineWidth: 0.6)
                    )
                    .shadow(color: canSend ? AppColors.calorie.opacity(0.35) : .clear, radius: 8, x: 0, y: 4)
            }
            .disabled(!canSend)
            .padding(.trailing, 5)
            .animation(.easeInOut(duration: 0.15), value: canSend)
        }
        .background(
            Capsule(style: .continuous).fill(.ultraThinMaterial)
        )
        .overlay(
            Capsule(style: .continuous)
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

    private var canSend: Bool {
        !isSending && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: - Send

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }

        chatStore.append(ChatMessage(role: .user, content: text))
        draft = ""
        errorMessage = nil
        isSending = true
        let historyForCall = chatStore.contextMessages().dropLast()  // exclude the user msg we just appended

        Task {
            defer { isSending = false }
            do {
                let reply = try await ChatService.sendMessage(
                    history: Array(historyForCall),
                    newUserMessage: text,
                    profile: userProfile,
                    weights: weightStore.entries,
                    foods: foodStore.entries,
                    useMetric: useMetric
                )
                chatStore.append(ChatMessage(role: .assistant, content: reply))
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}

// MARK: - Supporting views

private struct MessageBubble: View {
    let message: ChatMessage

    private var isUser: Bool { message.role == .user }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if !isUser {
                assistantBadge
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

    private var assistantBadge: some View {
        ZStack {
            Circle()
                .fill(.ultraThinMaterial)
                .frame(width: 26, height: 26)
                .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 0.5))
            Image(systemName: "sparkles")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                )
        }
        .padding(.top, 8)
    }

    private var bubble: some View {
        Text(message.content)
            .font(.system(.body, design: .rounded))
            .textSelection(.enabled)
            .foregroundStyle(isUser ? .white : .primary)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(bubbleBackground)
            .overlay(bubbleStroke)
            .overlay(alignment: .top) {
                if isUser { bubbleHighlight }
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: isUser ? AppColors.calorie.opacity(0.28) : Color.black.opacity(0.12),
                    radius: isUser ? 10 : 6, x: 0, y: isUser ? 6 : 3)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if isUser {
            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(AppColors.calorie.opacity(0.035))
            }
        }
    }

    private var bubbleStroke: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
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
