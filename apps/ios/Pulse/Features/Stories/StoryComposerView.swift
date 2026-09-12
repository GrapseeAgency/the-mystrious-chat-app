import PhotosUI
import SwiftUI

/// Full-screen story composer (Wave 4): Text/Photo pill toggle, hard 280-char
/// caption with live counter, 8 gradient swatches (text mode), explicit Post
/// button (Enter inserts a newline — never send-on-Enter), photo pick →
/// compress (≤1280px JPEG q0.82) → upload → preview + optional caption,
/// spinner states and user-visible errors. Success closes via [onPublished].
struct StoryComposerView: View {
    @ObservedObject var session: PulseSession
    @ObservedObject var stories: StoriesSessionModel
    let onPublished: () -> Void
    let onClose: () -> Void

    @State private var cs = StoryComposerState()
    @State private var localPreview: UIImage?
    @State private var photoItem: PhotosPickerItem?
    @State private var pickerPresent = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.035, blue: 0.043).ignoresSafeArea()

            VStack(spacing: 12) {
                header
                modePills
                stage
                captionRow
                if cs.mode == .text { swatches }
                if let error = cs.error { errorBanner(error) }
                Spacer(minLength: 0)
            }
            .padding(14)
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task { await pickAndUpload(item) }
        }
        .photosPicker(isPresented: $pickerPresent, selection: $photoItem, matching: .images)
    }

    // ── header ───────────────────────────────────────────────

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Color.white.opacity(0.10)))
            }
            .accessibilityLabel("Close composer")

            Text("New status")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
            Spacer()

            Button {
                post()
            } label: {
                HStack(spacing: 8) {
                    if cs.posting {
                        ProgressView().tint(.white).controlSize(.small)
                    }
                    Text("Post")
                        .font(.system(size: 14, weight: .bold))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Capsule().fill(cs.canPost ? StoryPalette.pair(for: "emerald").1 : Color.white.opacity(0.14)))
                .foregroundStyle(.white)
            }
            .disabled(!cs.canPost)
            .accessibilityLabel("Post status")
        }
    }

    // ── mode pills ───────────────────────────────────────────

    private var modePills: some View {
        HStack(spacing: 0) {
            modePill("Text", systemImage: "textformat", active: cs.mode == .text) {
                cs = cs.withMode(.text)
            }
            modePill("Photo", systemImage: "photo", active: cs.mode == .photo) {
                cs = cs.withMode(.photo)
                // Web parity: switching to Photo auto-opens the picker when
                // no photo is staged yet (story-composer-sheet.tsx).
                if cs.imagePath == nil && localPreview == nil {
                    pickerPresent = true
                }
            }
        }
        .background(Capsule().fill(Color.white.opacity(0.10)))
    }

    private func modePill(_ label: String, systemImage: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button {
            PulseHaptics.tap()
            action()
        } label: {
            Label(label, systemImage: systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Capsule().fill(active ? StoryPalette.pair(for: "emerald").1 : Color.clear))
        }
    }

    // ── stage ────────────────────────────────────────────────

    @ViewBuilder
    private var stage: some View {
        switch cs.mode {
        case .text:
            ZStack {
                StoryPalette.gradient(for: cs.background)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                TextField(
                    "Type your status…",
                    text: Binding(get: { cs.caption }, set: { cs = cs.withCaption($0) }),
                    axis: .vertical,
                )
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .tint(.white)
                .padding(.horizontal, 20)
            }
        case .photo:
            ZStack {
                RoundedRectangle(cornerRadius: 22).fill(Color.white.opacity(0.08))
                if cs.uploading {
                    VStack(spacing: 10) {
                        ProgressView().tint(.white.opacity(0.7))
                        Text("Uploading…")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.white.opacity(0.75))
                    }
                } else if let localPreview {
                    Image(uiImage: localPreview)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 22))
                } else if let path = cs.imagePath, let url = PulseEndpoints.mediaURL(path) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFit()
                        } else {
                            ProgressView().tint(.white.opacity(0.6))
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                } else {
                    Text("Pick a photo to share")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if cs.imagePath != nil || localPreview != nil {
                    Button {
                        PulseHaptics.tap()
                        cs = cs.withImage(nil)
                        localPreview = nil
                    } label: {
                        Image(systemName: "arrow.counterclockwise")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.black.opacity(0.55)))
                    }
                    .padding(10)
                    .accessibilityLabel("Change photo")
                }
            }
        }
    }

    // ── caption + counter ────────────────────────────────────

    private var captionRow: some View {
        HStack(spacing: 10) {
            TextField(
                cs.mode == .text ? "Type your status…" : "Add a caption (optional)…",
                text: Binding(get: { cs.caption }, set: { cs = cs.withCaption($0) }),
                axis: .vertical,
            )
            .font(.system(size: 15))
            .foregroundStyle(.white)
            .tint(.white)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.10)))

            let remaining = StoryComposerState.captionMax - cs.caption.count
            Text("\(remaining)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(remaining <= 20 ? StoryPalette.pair(for: "rose").1 : Color.white.opacity(0.4))
        }
    }

    // ── swatches (text mode only — wire rule) ────────────────

    private var swatches: some View {
        HStack(spacing: 10) {
            ForEach(StoryPalette.keys, id: \.self) { key in
                let selected = key == cs.background
                Button {
                    PulseHaptics.tap()
                    cs = cs.withBackground(key)
                } label: {
                    Circle()
                        .fill(StoryPalette.gradient(for: key))
                        .frame(width: 34, height: 34)
                        .overlay(
                            Circle().strokeBorder(
                                selected ? Color.white : Color.white.opacity(0.25),
                                lineWidth: selected ? 2.5 : 1,
                            )
                        )
                }
                .accessibilityLabel("Background \(key)")
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(StoryPalette.pair(for: "rose").1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(StoryPalette.pair(for: "rose").1.opacity(0.10)))
    }

    // ── actions ──────────────────────────────────────────────

    private func post() {
        guard cs.canPost else { return }
        let background = cs.mode == .text ? cs.background : nil
        let imagePath = cs.mode == .photo ? cs.imagePath : nil
        cs = cs.withPosting(true)
        Task {
            let error = await stories.publish(caption: cs.caption, background: background, imagePath: imagePath)
            if let error {
                cs = cs.withPosting(false).withError(error)
            } else {
                onPublished()
            }
        }
    }

    /// Pick → re-encode (≤1280px JPEG q0.82 — the exact wire policy) →
    /// POST /api/uploads → imagePath. Failures are visible, never silent.
    private func pickAndUpload(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let jpeg = PulseMediaSupport.downscaledJPEGData(from: data) else {
            cs = cs.withError("Couldn't read that image — try another one")
            return
        }
        localPreview = UIImage(data: jpeg)
        cs = cs.withUploading(true)
        do {
            let dataUrl = PulseMediaSupport.dataUrl(mime: "image/jpeg", data: jpeg)
            let imagePath = try await session.api.uploadMedia(dataUrl: dataUrl)
            cs = cs.withImage(imagePath).withUploading(false)
        } catch {
            localPreview = nil
            cs = cs.withUploading(false).withError("Upload failed — check your connection and retry")
        }
    }
}
