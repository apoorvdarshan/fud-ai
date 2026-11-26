import SwiftUI

/// The running rest countdown, pinned above the workout log's + button.
struct WorkoutRestTimerBar: View {
    let timer: WorkoutRestTimer
    let onExtend: () -> Void
    let onSkip: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        TimelineView(.periodic(from: timer.startedAt, by: 1)) { context in
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .stroke(Color.workoutAccent.opacity(0.18), lineWidth: 4)
                    Circle()
                        .trim(from: 0, to: 1 - timer.progress(at: context.date))
                        .stroke(Color.workoutAccent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: "hourglass")
                        .font(.caption.weight(.heavy))
                        .foregroundStyle(Color.workoutAccent)
                }
                .frame(width: 38, height: 38)
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 1) {
                    // The countdown is what the bar is for, so it never truncates
                    // or wraps: a long translation of Rest gives way instead.
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("Rest")
                            .font(.system(.caption, design: .rounded, weight: .heavy))
                            .foregroundStyle(Color.workoutMutedText)
                            .lineLimit(1)
                        Text(WorkoutRestTimer.clockText(seconds: timer.remainingSeconds(at: context.date)))
                            .font(.system(.title3, design: .rounded, weight: .heavy).monospacedDigit())
                            .foregroundStyle(Color.workoutCharcoal)
                            .contentTransition(.numericText(countsDown: true))
                            .lineLimit(1)
                            .fixedSize()
                            .layoutPriority(1)
                    }
                    Text(timer.exerciseName)
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(Color.workoutMutedText)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                Button(action: onExtend) {
                    Text("+15s")
                        .font(.system(.subheadline, design: .rounded, weight: .heavy).monospacedDigit())
                        .lineLimit(1)
                        .frame(minWidth: 44, minHeight: 36)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(Color.workoutAccent)
                .accessibilityLabel("Add 15 seconds")

                Button(action: onSkip) {
                    Text("Skip")
                        .font(.system(.subheadline, design: .rounded, weight: .heavy))
                        .lineLimit(1)
                        .frame(minWidth: 44, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(Color.workoutAccent)
                .accessibilityLabel("Skip rest")
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(Color.workoutPanel, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.82), lineWidth: 0.8)
        }
        .shadow(color: Color.black.opacity(colorScheme == .light ? 0.10 : 0.30), radius: 12, x: 0, y: 6)
        .accessibilityIdentifier("workout.rest.bar")
    }
}

/// Starts (or restarts) rest after one set, for sets whose reps were already
/// filled in, such as those planned by a routine.
struct WorkoutRestSetButton: View {
    let setNumber: Int
    let isResting: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isResting ? "hourglass.circle.fill" : "hourglass")
                .font(.system(size: 15, weight: .bold))
                .frame(width: 32, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isResting ? Color.workoutAccent : Color.workoutMutedText.opacity(0.72))
        .accessibilityLabel(Text("Start rest after set \(setNumber)"))
    }
}
