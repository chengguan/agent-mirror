import AVFoundation
import UIKit
import Shared

/// Presents a full-screen AVFoundation QR reader. The payload (contains tok)
/// is handed to Kotlin and never logged.
final class IosQrScanHost: QrScanHost {
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
            cancel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
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
