import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import Pulse

@MainActor
final class ImagePreparationTests: XCTestCase {
    private func image(width: Int, height: Int, orientation: Int = 1) throws -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: CGFloat(width), height: CGFloat(height)), format: format).image { _ in }
        let data = NSMutableData()
        let type = orientation == 1 ? UTType.png : UTType.jpeg
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(data as CFMutableData, type.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, try XCTUnwrap(image.cgImage), [kCGImagePropertyOrientation: orientation] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
    func testFileAndDataImportsProduceBoundedOpaqueJPEGs() throws {
        let original = try image(width: 3000, height: 1500)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        try original.write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        for result in [PulseMediaSupport.downscaledJPEGData(from: original), PulseMediaSupport.downscaledJPEGData(from: file)] {
            let jpeg = try XCTUnwrap(result)
            let source = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
            XCTAssertEqual(CGImageSourceGetType(source) as String?, UTType.jpeg.identifier)
            let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
            XCTAssertEqual(image.width, 1280)
            XCTAssertEqual(image.height, 640)
            var pixel = [UInt8](repeating: 0, count: 4)
            pixel.withUnsafeMutableBytes { bytes in
                let context = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            }
            XCTAssertGreaterThan(pixel[0], 245)
            XCTAssertGreaterThan(pixel[1], 245)
            XCTAssertGreaterThan(pixel[2], 245)
        }
    }
    func testExifRotationIsAppliedAndThinImagesStayNonzero() throws {
        let data = try image(width: 120, height: 80, orientation: 6)
        let jpeg = try XCTUnwrap(PulseMediaSupport.downscaledJPEGData(from: data))
        let rotated = try XCTUnwrap(UIImage(data: jpeg)?.cgImage)
        XCTAssertEqual(rotated.width, 80)
        XCTAssertEqual(rotated.height, 120)
        let thin = try XCTUnwrap(PulseMediaSupport.downscaledJPEGData(from: image(width: 1, height: 5000)))
        let output = try XCTUnwrap(UIImage(data: thin)?.cgImage)
        XCTAssertEqual(output.width, 1)
        XCTAssertEqual(output.height, 1280)
        XCTAssertNil(PulseMediaSupport.downscaledJPEGData(from: Data("not an image".utf8)))
    }
    func testOversizedSourceFileIsRejectedBeforeDecoding() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        XCTAssertTrue(FileManager.default.createFile(atPath: file.path, contents: Data()))
        defer { try? FileManager.default.removeItem(at: file) }
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: UInt64(PulseMediaSupport.maxImageSourceBytes + 1))
        try handle.close()
        XCTAssertNil(PulseMediaSupport.downscaledJPEGData(from: file))
    }

    func testCameraUIImagePathUsesBoundedPixelsWithoutAFullResolutionIntermediateJPEG() throws {
        let original = try XCTUnwrap(UIImage(data: image(width: 3000, height: 1500)))
        let scaledSource = UIImage(cgImage: try XCTUnwrap(original.cgImage), scale: 3, orientation: .up)
        let jpeg = try XCTUnwrap(PulseMediaSupport.downscaledJPEGData(from: scaledSource))
        let result = try XCTUnwrap(UIImage(data: jpeg)?.cgImage)
        XCTAssertEqual(result.width, 1280)
        XCTAssertEqual(result.height, 640)
        XCTAssertNil(PulseMediaSupport.downscaledJPEGData(from: UIImage()))
    }
    func testUnavailableCameraDoesNotRequestOrPresentAnInvalidSource() {
        guard !CameraPicker.isAvailable else { return }
        var response: Bool?
        CameraPicker.requestAccess { response = $0 }
        XCTAssertEqual(response, false)
    }

    func testDocumentReaderRefusesEmptyOversizedAndNonFileInputs() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
        try Data().write(to: file); defer { try? FileManager.default.removeItem(at: file) }
        XCTAssertThrowsError(try PulseMediaSupport.readDocument(url: file))
        let content = Data("a real document".utf8); try content.write(to: file)
        XCTAssertEqual(try PulseMediaSupport.readDocument(url: file), content)
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: UInt64(PulseMediaSupport.maxDocumentBytes + 1)); try handle.close()
        XCTAssertThrowsError(try PulseMediaSupport.readDocument(url: file))
        XCTAssertThrowsError(try PulseMediaSupport.readDocument(url: URL(string: "https://document.test/private.txt")!))
    }

    func testCancelledDocumentReadDoesNotReturnBytes() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
        try Data("document".utf8).write(to: file); defer { try? FileManager.default.removeItem(at: file) }
        let read = Task { try PulseMediaSupport.readDocument(url: file) }
        read.cancel()
        do { _ = try await read.value; XCTFail("Cancelled read should not return bytes") }
        catch is CancellationError { }
    }
    func testReplacingPreparedRoomMediaPreservesLiveCaptionAndImagePrivacy() throws {
        let session = PulseSession()
        let room = try JSONDecoder().decode(WireConversationSummary.self,
            from: Data(#"{"id":"media-room","isGroup":false,"members":[]}"#.utf8))
        let model = RoomViewModel(conversation: room, session: session, beginAutomaticWork: false)
        let first = RoomViewModel.StagedMedia(kind: .image, dataUrl: "first", fileName: nil,
            fileSize: 1, previewURL: nil, viewOnce: true)
        model.installPreparedAttachment(first)
        model.caption = "caption edited while replacement loads"
        model.installPreparedAttachment(.init(kind: .image, dataUrl: "replacement", fileName: nil,
            fileSize: 2, previewURL: nil, viewOnce: false))
        XCTAssertEqual(model.staged?.dataUrl, "replacement")
        XCTAssertEqual(model.staged?.viewOnce, true)
        XCTAssertEqual(model.caption, "caption edited while replacement loads")
        model.setStagedViewOnce(false)
        model.installPreparedAttachment(.init(kind: .image, dataUrl: "third", fileName: nil,
            fileSize: 3, previewURL: nil, viewOnce: false))
        XCTAssertEqual(model.staged?.viewOnce, false)
        model.installPreparedAttachment(.init(kind: .file, dataUrl: "document", fileName: "notes.txt",
            fileSize: 4, previewURL: nil, viewOnce: false))
        XCTAssertEqual(model.caption, "caption edited while replacement loads")
        XCTAssertEqual(model.staged?.viewOnce, false)
        model.cancelStaged()
        XCTAssertNil(model.staged)
        XCTAssertEqual(model.caption, "")
    }

    func testProfileAvatarAndCoverUseDifferentBoundedCrops() throws {
        let original = try image(width: 3000, height: 1500)
        let avatar = try XCTUnwrap(UIImage(data: try XCTUnwrap(PulseAvatarImage.jpegData(from: original)))?.cgImage)
        let cover = try XCTUnwrap(UIImage(data: try XCTUnwrap(PulseCoverImage.jpegData(from: original)))?.cgImage)
        XCTAssertEqual(avatar.width, 512); XCTAssertEqual(avatar.height, 512)
        XCTAssertEqual(cover.width, 1200); XCTAssertEqual(cover.height, 600)
        let thin = try image(width: 1, height: 5000)
        let tiny = try XCTUnwrap(UIImage(data: try XCTUnwrap(PulseAvatarImage.jpegData(from: thin)))?.cgImage)
        XCTAssertGreaterThan(tiny.width, 0); XCTAssertGreaterThan(tiny.height, 0)
        XCTAssertNil(PulseAvatarImage.jpegData(from: Data("bad image".utf8)))
        XCTAssertNil(PulseCoverImage.jpegData(from: Data("bad image".utf8)))
    }

}
