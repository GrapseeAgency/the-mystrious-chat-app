import PhotosUI
import SwiftUI

/// Full-screen story composer (Wave 4): Text/Photo pill toggle, hard 280-char
/// caption with live counter, 8 gradient swatches (text mode), explicit Post
/// button (Enter inserts a newline - never send-on-Enter), photo pick →
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
    @State private var pickerOwner: UUID?
    @StateObject private var operations = PulseRoomOperations()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            // PULSE EMBER (EMB-I): the sunset ground behind the composer.
            Rectangle().fill(PulseTheme.emberBackdrop).ignoresSafeArea()

            VStack(spacing: 12) {
                header
                modePills.disabled(cs.posting)
                stage.disabled(cs.posting)
                captionRow.disabled(cs.posting)
                if cs.mode == .text { swatches.disabled(cs.posting) }
                if let error = cs.error { errorBanner(error) }
                Spacer(minLength: 0)
            }
            .padding(14)
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            let owner = pickerOwner
            pickerOwner = nil; photoItem = nil
            guard owner == session.sessionGeneration, cs.mode == .photo else { return }
            pickAndUpload(item)
        }
        .photosPicker(isPresented: $pickerPresent, selection: $photoItem, matching: .images)
        .onDisappear { operations.stop() }
        .onChange(of: session.sessionGeneration) { _, _ in
            operations.stop()
            pickerOwner = nil; pickerPresent = false
            cs = StoryComposerState(); localPreview = nil; photoItem = nil
            onClose()
        }
    }

    // header

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                pickerOwner = nil
                operations.stop()
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

    // mode pills

    private var modePills: some View {
        HStack(spacing: 0) {
            modePill("Text", systemImage: "textformat", active: cs.mode == .text) {
                cancelImageImport()
                cs = cs.withMode(.text)
            }
            modePill("Photo", systemImage: "photo", active: cs.mode == .photo) {
                cs = cs.withMode(.photo)
                // Replacing a photo must not require deleting the confirmed one first.
                // Capture the picker launch owner, not whichever account receives its callback.
                pickerOwner = session.sessionGeneration
                pickerPresent = true
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

    // stage

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
                    // R45 re-audit: this preview stays HEADERLESS on purpose.
                    // The draft's image is uploaded but not yet posted - the
                    // server has no story row to grant against (upload files
                    // carry no owner record), so an authenticated fetch would
                    // 403 the preview after a composer-state restore. The
                    // moment the story is posted, its 24h lifetime binds and
                    // every other surface fetches authenticated.
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
                        cancelImageImport()
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

    // caption + counter

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

            let remaining = StoryComposerState.captionMax - cs.caption.utf16.count
            Text("\(remaining)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(remaining <= 20 ? StoryPalette.pair(for: "rose").1 : Color.white.opacity(0.4))
        }
    }

    // swatches (text mode only - wire rule)

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

    // actions

    private func cancelImageImport() {
        guard !cs.posting else { return }
        pickerOwner = nil
        operations.stop()
        cs = cs.withUploading(false)
    }

    private func post() {
        guard cs.canPost else { return }
        let submitted = cs
        let generation = session.sessionGeneration
        cs = cs.withPosting(true)
        operations.run(key: "publish", operation: {
            guard !Task.isCancelled, session.sessionGeneration == generation else { return false }
            let error = await stories.publish(caption: submitted.caption,
                background: submitted.mode == .text ? submitted.background : nil,
                imagePath: submitted.mode == .photo ? submitted.imagePath : nil)
            guard !Task.isCancelled, session.sessionGeneration == generation else { return false }
            if let error { cs = cs.withPosting(false).withError(error); return false }
            return true
        }, onSuccess: onPublished)
    }

    /// The presentation owns both preparation and upload; a new selection invalidates
    /// the old result. Keep the previous confirmed photo if its replacement fails.
    private func pickAndUpload(_ item: PhotosPickerItem) {
        guard !cs.posting else { return }
        cancelImageImport()
        let generation = session.sessionGeneration
        let api = session.api
        cs = cs.withUploading(true)
        operations.run(key: "photo", operation: {
            guard !Task.isCancelled, session.sessionGeneration == generation else { return false }
            do {
                guard let photo = try await item.loadTransferable(type: PulsePickedPhoto.self) else { throw CocoaError(.fileReadCorruptFile) }
                try Task.checkCancellation()
                guard session.sessionGeneration == generation else { return false }
                let path = try await api.uploadMedia(dataUrl: PulseMediaSupport.dataUrl(mime: "image/jpeg", data: photo.jpeg))
                try Task.checkCancellation()
                guard session.sessionGeneration == generation else { return false }
                localPreview = UIImage(data: photo.jpeg)
                cs = cs.withImage(path).withUploading(false)
                return true
            } catch {
                guard !Task.isCancelled, session.sessionGeneration == generation else { return false }
                cs = cs.withUploading(false).withError("Couldn't prepare or upload that photo. Any previous photo is kept; try again.")
                return false
            }
        })
    }
}
