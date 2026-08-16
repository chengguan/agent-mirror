import AVFoundation
import PhotosUI
import UIKit
import Vision
import Shared

/// Presents a full-screen AVFoundation QR reader, or the system photo picker.
/// The payload (contains tok) is handed to Kotlin and never logged.
final class IosQrScanHost: QrScanHost {
    private var albumPicker: AlbumQrPicker?

    func scan(onResult: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else {
                onResult(nil, "Could not open the camera.")
                return
            }
            let scanner = QrScanViewController { payload, error in
                DispatchQueue.main.async { onResult(payload, error) }
            }
            presenter.present(scanner, animated: true)
        }
    }

    func pickFromAlbum(onResult: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else {
                onResult(nil, "Could not open Photos.")
                return
            }
            let picker = AlbumQrPicker { [weak self] payload, error in
                self?.albumPicker = nil
                DispatchQueue.main.async { onResult(payload, error) }
            }
            self.albumPicker = picker
            presenter.present(picker.makeController(), animated: true)
        }
    }

    private static func topViewController(from base: UIViewController? = nil) -> UIViewController? {
        let root = base ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
        if let nav = root as? UINavigationController {
            return topViewController(from: nav.visibleViewController)
        }
        if let tab = root as? UITabBarController {
            return topViewController(from: tab.selectedViewController)
        }
        if let presented = root?.presentedViewController {
            return topViewController(from: presented)
        }
        return root
    }
}

private final class QrScanViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onResult: (String?, String?) -> Void
    private let session = AVCaptureSession()
    private var finished = false
    private let hint = UILabel()
    private var preview: AVCaptureVideoPreviewLayer?
    private var albumPicker: AlbumQrPicker?

    init(onResult: @escaping (String?, String?) -> Void) {
        self.onResult = onResult
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalPresentationCapturesStatusBarAppearance = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.04, green: 0.04, blue: 0.04, alpha: 1)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        requestCamera()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        if session.isRunning { session.stopRunning() }
    }

    @objc private func appDidBackground() {
        // Permission dialog is not a background transition; only leave if the
        // app is actually backgrounded so the pairing QR is not left on screen.
        finish(payload: nil, error: nil)
    }

    private func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.finish(payload: nil, error: "Camera access is off. Enable it in Settings to scan the QR.")
                    }
                }
            }
        case .denied, .restricted:
            finish(payload: nil, error: "Camera access is off. Enable it in Settings to scan the QR.")
        @unknown default:
            finish(payload: nil, error: "Camera is not available.")
        }
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video) else {
            finish(payload: nil, error: "This device has no camera. Paste the pairing URL instead.")
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .high
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) { session.addInput(input) }
        } catch {
            session.commitConfiguration()
            finish(payload: nil, error: "Could not start the camera.")
            return
        }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            if output.availableMetadataObjectTypes.contains(.qr) {
                output.metadataObjectTypes = [.qr]
            }
        }
        session.commitConfiguration()

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.insertSublayer(preview, at: 0)
        self.preview = preview
        addChrome()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    private func addChrome() {
        hint.text = "Point at the pairing QR"
        hint.textColor = UIColor(white: 0.88, alpha: 1)
        hint.font = .systemFont(ofSize: 16, weight: .medium)
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        let photos = UIButton(type: .system)
        photos.setTitle("Choose from Photos", for: .normal)
        photos.setTitleColor(UIColor(red: 0.10, green: 0.74, blue: 0.61, alpha: 1), for: .normal)
        photos.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        photos.addTarget(self, action: #selector(photosTapped), for: .touchUpInside)
        photos.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(photos)

        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.setTitleColor(UIColor(red: 0.10, green: 0.74, blue: 0.61, alpha: 1), for: .normal)
        cancel.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancel)

        NSLayoutConstraint.activate([
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            photos.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            photos.bottomAnchor.constraint(equalTo: cancel.topAnchor, constant: -12),
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    @objc private func photosTapped() {
        let picker = AlbumQrPicker { [weak self] payload, error in
            guard let self else { return }
            self.albumPicker = nil
            if let payload {
                self.finish(payload: payload, error: nil)
            } else if let error {
                self.hint.text = error
            }
        }
        albumPicker = picker
        present(picker.makeController(), animated: true)
    }

    @objc private func cancelTapped() {
        finish(payload: nil, error: nil)
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !finished else { return }
        for object in metadataObjects {
            guard let code = object as? AVMetadataMachineReadableCodeObject,
                  code.type == .qr,
                  let value = code.stringValue
            else { continue }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if QrScannerKt.looksLikePairingPayload(raw: trimmed) {
                finish(payload: trimmed, error: nil)
                return
            }
            hint.text = "Not a Mirror pairing QR"
        }
    }

    private func finish(payload: String?, error: String?) {
        guard !finished else { return }
        finished = true
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.stopRunning()
            }
        }
        let deliver = { self.onResult(payload, error) }
        if presentingViewController != nil {
            dismiss(animated: true, completion: deliver)
        } else {
            deliver()
        }
    }
}

/// System photo picker (no Photo Library permission). Decodes a pairing QR
/// on-device with Vision and never logs the payload.
private final class AlbumQrPicker: NSObject, PHPickerViewControllerDelegate {
    private let onResult: (String?, String?) -> Void
    private var finished = false

    init(onResult: @escaping (String?, String?) -> Void) {
        self.onResult = onResult
    }

    func makeController() -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        return picker
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        let item = results.first?.itemProvider
        picker.dismiss(animated: true) { [weak self] in
            guard let self else { return }
            guard let item else {
                self.deliver(payload: nil, error: nil)
                return
            }
            self.loadQr(from: item)
        }
    }

    private func loadQr(from item: NSItemProvider) {
        guard item.canLoadObject(ofClass: UIImage.self) else {
            deliver(payload: nil, error: "Could not read that photo.")
            return
        }
        item.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let image = object as? UIImage else {
                    DispatchQueue.main.async {
                        self?.deliver(payload: nil, error: "Could not read that photo.")
                    }
                    return
                }
                let (payload, error) = detectPairingQr(in: image)
                DispatchQueue.main.async {
                    self?.deliver(payload: payload, error: error)
                }
            }
        }
    }

    private func deliver(payload: String?, error: String?) {
        guard !finished else { return }
        finished = true
        onResult(payload, error)
    }
}

private func detectPairingQr(in image: UIImage) -> (String?, String?) {
    let cgImage: CGImage
    let orientation: CGImagePropertyOrientation
    if let existing = image.cgImage {
        cgImage = existing
        orientation = cgOrientation(image.imageOrientation)
    } else if let rendered = rasterizedCgImage(image) {
        cgImage = rendered
        orientation = .up
    } else {
        return (nil, "Could not read that photo.")
    }
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation)
    do {
        try handler.perform([request])
    } catch {
        return (nil, "Could not read a QR code in that photo.")
    }
    let payloads = (request.results ?? []).compactMap { result -> String? in
        let value = result.payloadStringValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : value
    }
    if let match = payloads.first(where: { QrScannerKt.looksLikePairingPayload(raw: $0) }) {
        return (match, nil)
    }
    if payloads.isEmpty {
        return (nil, "No QR code in that photo.")
    }
    return (nil, "That photo is not a Mirror pairing QR.")
}

private func cgOrientation(_ value: UIImage.Orientation) -> CGImagePropertyOrientation {
    switch value {
    case .up: return .up
    case .down: return .down
    case .left: return .left
    case .right: return .right
    case .upMirrored: return .upMirrored
    case .downMirrored: return .downMirrored
    case .leftMirrored: return .leftMirrored
    case .rightMirrored: return .rightMirrored
    @unknown default: return .up
    }
}

private func rasterizedCgImage(_ image: UIImage) -> CGImage? {
    let format = UIGraphicsImageRendererFormat.default()
    format.scale = image.scale
    format.opaque = true
    return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
        image.draw(in: CGRect(origin: .zero, size: image.size))
    }.cgImage
}
