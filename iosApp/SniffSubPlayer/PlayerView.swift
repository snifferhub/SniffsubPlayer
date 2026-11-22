import ComposeApp
import AVFoundation
import AVKit
import MediaPlayer
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WebKit

struct PlayerView: View {
    private enum PendingSubtitleAction {
        case search
        case importFile
        case paste
    }

    let candidate: ComposeApp.MediaCandidate

    @Environment(\.dismiss) private var dismiss
    @AppStorage("SniffSubPlayer.playerAccentColor") private var playerAccentColorID = PlayerAccentColor.defaultValue
    @State private var player = AVPlayer()
    @StateObject private var pictureInPicture = PlayerPictureInPicture()
    @State private var timeObserver: Any?
    @State private var itemObservations: [NSKeyValueObservation] = []
    @State private var cues: [SubtitleCue] = []
    @State private var subtitleTimeline = SubtitleTimeline()
    @State private var subtitleText = ""
    @State private var subtitleOffset = 0.0
    @State private var subtitleSize = 30.0
    @State private var subtitleBottom = 38.0
    @State private var showingSubtitleImporter = false
    @State private var showingPasteSheet = false
    @State private var showingSubtitleSearch = false
    @State private var showingSubtitleSettings = false
    @State private var pendingSubtitleAction: PendingSubtitleAction?
    @State private var subtitlecatPage: WebSheetPage?
    @State private var pastedSubtitle = ""
    @State private var status = ""
    @State private var isPlaying = false
    @State private var wantsPlayback = false
    @State private var playbackRate: Float = 1
    @State private var isHoldingSpeed = false
    @State private var currentTime = 0.0
    @State private var duration = 0.0
    @State private var seekableRanges: [ClosedRange<Double>] = []
    @State private var isScrubbing = false
    @State private var isDraggingSeek = false
    @State private var seekRevision = 0
    @State private var searchQuery = ""
    @State private var onlineSubtitles: [OnlineSubtitle] = []
    @State private var isSearchingSubtitles = false
    @State private var hasSearchedSubtitles = false
    @State private var downloadingSubtitleURL: String?
    @State private var subtitleSearchMessage = ""
    @State private var showControls = true
    @State private var hideControlsTask: Task<Void, Never>?
    @State private var seekFeedbackText = ""
    @State private var seekFeedbackIcon = "goforward.15"
    @State private var showSeekFeedback = false
    @State private var seekFeedbackTask: Task<Void, Never>?
    @State private var panStartTime = 0.0
    @State private var brightnessStart = UIScreen.main.brightness
    @State private var volumeStart: Float = 1
    @State private var systemVolume = PlayerSystemVolumeControl()

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()

            PlayerLayerView(
                player: player,
                pictureInPicture: pictureInPicture,
                allowsAutomaticPictureInPicture: wantsPlayback && !isScrubbing &&
                    !showingSubtitleSettings && !showingSubtitleSearch &&
                    !showingSubtitleImporter && !showingPasteSheet,
                systemVolume: systemVolume,
                onSingleTap: toggleControlsVisibility,
                onDoubleTap: handleDoubleTap,
                onPan: handlePan,
                onVerticalPan: handleVerticalPan,
                onHoldChanged: setHoldingSpeed,
                canSeek: playbackRange != nil
            )
                .ignoresSafeArea()
                .allowsHitTesting(!pictureInPicture.isPresenting)

            if pictureInPicture.isPresenting {
                Button {
                    pictureInPicture.stop()
                } label: {
                    Label {
                        Text("返回全屏")
                    } icon: {
                        Image(uiImage: AVPictureInPictureController.pictureInPictureButtonStopImage)
                            .renderingMode(.template)
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(.ultraThinMaterial, in: Capsule())
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            if !pictureInPicture.isPresenting && isHoldingSpeed {
                Label(rateLabel(effectivePlaybackRate), systemImage: "forward.fill")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.68), in: Capsule())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 18)
                    .allowsHitTesting(false)
            }

            if !pictureInPicture.isPresenting && showSeekFeedback {
                Label(seekFeedbackText, systemImage: seekFeedbackIcon)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    .background(.black.opacity(0.68), in: Capsule())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                    .offset(y: 86)
                    .transition(.scale(scale: 0.9).combined(with: .opacity))
                    .allowsHitTesting(false)
            }

            if !pictureInPicture.isPresenting && !subtitleText.isEmpty {
                Text(subtitleText)
                    .font(.system(size: subtitleSize, weight: .bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .shadow(color: .black, radius: 4, x: 0, y: 2)
                    .shadow(color: .black, radius: 10, x: 0, y: 0)
                    .padding(.horizontal, 18)
                    .padding(.bottom, subtitleBottom)
                    .allowsHitTesting(false)
            }

            if !pictureInPicture.isPresenting && showControls {
                controls
                    .transition(.opacity)
            }

            if showingSubtitleSettings {
                subtitleSettingsOverlay
                    .transition(.scale(scale: 0.96).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: showControls)
        .animation(.easeInOut(duration: 0.2), value: showingSubtitleSettings)
        .onChange(of: playbackRate) { _ in
            applyPlaybackRate()
            keepControlsVisibleForInteraction()
        }
        .onChange(of: pictureInPicture.isPresenting) { isPresenting in
            if isPresenting {
                hideControlsTask?.cancel()
            } else {
                wantsPlayback = player.timeControlStatus != .paused
                isPlaying = player.timeControlStatus == .playing
                keepControlsVisibleForInteraction()
            }
        }
        .onChange(of: pictureInPicture.errorMessage) { message in
            if message != nil {
                keepControlsVisibleForInteraction()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
            setHoldingSpeed(false)
        }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notification in
            guard let item = notification.object as? AVPlayerItem, item === player.currentItem else { return }
            wantsPlayback = false
            isPlaying = false
            if duration > 0 { currentTime = duration }
            setHoldingSpeed(false)
            showControls = true
            hideControlsTask?.cancel()
        }
        .fileImporter(
            isPresented: $showingSubtitleImporter,
            allowedContentTypes: subtitleContentTypes,
            allowsMultipleSelection: false,
            onCompletion: importSubtitle
        )
        .sheet(isPresented: $showingPasteSheet) {
            pasteSubtitleSheet
        }
        .sheet(isPresented: $showingSubtitleSearch, onDismiss: performPendingSubtitleAction) {
            subtitleSearchSheet
        }
        .onAppear {
            searchQuery = VideoIDExtractor.extract(from: [candidate.pageUrl, candidate.url])
            OrientationManager.lock([.landscapeLeft, .landscapeRight], preferred: .landscapeRight)
            configureAudioSession()
            configurePlayer()
            addTimeObserver()
            scheduleControlsAutoHide()
        }
        .onDisappear {
            pictureInPicture.stop()
            hideControlsTask?.cancel()
            seekFeedbackTask?.cancel()
            seekRevision += 1
            wantsPlayback = false
            isHoldingSpeed = false
            player.pause()
            if let timeObserver {
                player.removeTimeObserver(timeObserver)
            }
            timeObserver = nil
            itemObservations.forEach { $0.invalidate() }
            itemObservations.removeAll()
            player.replaceCurrentItem(with: nil)
            OrientationManager.unlock()
        }
    }

    private var playerAccentColor: Color {
        PlayerAccentColor.color(from: playerAccentColorID)
    }

    private var effectivePlaybackRate: Float {
        isHoldingSpeed ? max(2, playbackRate) : playbackRate
    }

    private var playbackRange: ClosedRange<Double>? {
        if let range = seekableRanges.first(where: { $0.contains(currentTime) }) ?? seekableRanges.last {
            return range
        }
        guard duration > 0, player.currentItem?.status == .readyToPlay else { return nil }
        return 0...duration
    }

    private var controls: some View {
        ZStack {
            LinearGradient(
                colors: [.black.opacity(0.62), .clear, .black.opacity(0.76)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 38, height: 38)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .accessibilityLabel("关闭播放器")

                    let playerStatus = pictureInPicture.errorMessage ?? status
                    if !playerStatus.isEmpty {
                        Text(playerStatus)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.76))
                            .lineLimit(1)
                            .padding(.leading, 8)
                    }

                    Spacer()

                    if AVPictureInPictureController.isPictureInPictureSupported() {
                        Button {
                            setHoldingSpeed(false)
                            pictureInPicture.start()
                        } label: {
                            Image(uiImage: AVPictureInPictureController.pictureInPictureButtonStartImage)
                                .renderingMode(.template)
                                .frame(width: 44, height: 38)
                                .background(.ultraThinMaterial, in: Capsule())
                        }
                        .disabled(!pictureInPicture.isPossible || isScrubbing)
                        .accessibilityLabel("画中画")
                    }

                    Menu {
                        Picker("播放速度", selection: $playbackRate) {
                            ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 1.75, 2], id: \.self) { rate in
                                Text(rateLabel(rate)).tag(rate)
                            }
                        }
                    } label: {
                        Text(rateLabel(playbackRate))
                            .font(.subheadline.monospacedDigit().weight(.semibold))
                            .frame(minWidth: 52, minHeight: 44)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .simultaneousGesture(TapGesture().onEnded {
                        showControls = true
                        hideControlsTask?.cancel()
                    })
                    .accessibilityLabel("播放速度")
                    .accessibilityValue(rateLabel(playbackRate))

                    Button {
                        keepControlsVisibleForInteraction()
                        withAnimation {
                            showingSubtitleSettings = true
                        }
                    } label: {
                        Image(systemName: cues.isEmpty ? "captions.bubble" : "captions.bubble.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(width: 44, height: 38)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .accessibilityLabel("字幕设置")
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 22)
                .padding(.top, 14)

                Spacer()

                HStack(spacing: 10) {
                    Text(formatTime(currentTime))
                        .frame(width: 54, alignment: .leading)

                    PlayerProgressBar(
                        value: Binding(
                            get: { clampedPlaybackTime(currentTime) },
                            set: previewSeek
                        ),
                        range: playbackRange ?? 0...1,
                        accentColor: playerAccentColor,
                        onEditingChanged: { editing in
                            if editing { beginScrubbing() }
                            else { seek(to: currentTime) }
                        }
                    )
                    .disabled(playbackRange == nil)

                    Text(formatTime(duration > 0 ? duration : playbackRange?.upperBound ?? 0))
                        .frame(width: 54, alignment: .trailing)
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.9))
                .padding(.horizontal, 24)
                .padding(.bottom, 18)
            }

            HStack(spacing: 42) {
                playerControlButton(systemName: "gobackward.15", size: 26) {
                    keepControlsVisibleForInteraction()
                    seekBy(-15)
                }
                .disabled(playbackRange == nil)
                .accessibilityLabel("后退 15 秒")

                playerControlButton(systemName: wantsPlayback ? "pause.fill" : "play.fill", size: 31, prominent: true) {
                    keepControlsVisibleForInteraction()
                    togglePlay()
                }

                playerControlButton(systemName: "goforward.15", size: 26) {
                    keepControlsVisibleForInteraction()
                    seekBy(15)
                }
                .disabled(playbackRange == nil)
                .accessibilityLabel("快进 15 秒")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func playerControlButton(
        systemName: String,
        size: CGFloat,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: .semibold))
                .frame(width: prominent ? 70 : 54, height: prominent ? 70 : 54)
                .background(.ultraThinMaterial, in: Circle())
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.32), radius: 12, y: 5)
    }

    private var subtitleSettingsOverlay: some View {
        ZStack {
            Color.black.opacity(0.3)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation { showingSubtitleSettings = false }
                }

            VStack(spacing: 16) {
                HStack {
                    Text("字幕设置")
                        .font(.headline)
                    Spacer()
                    Button {
                        withAnimation { showingSubtitleSettings = false }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(spacing: 10) {
                    subtitleActionButton("在线搜索", systemImage: "magnifyingglass", action: .search)
                    subtitleActionButton("字幕文件", systemImage: "doc.badge.plus", action: .importFile)
                    subtitleActionButton("粘贴字幕", systemImage: "doc.on.clipboard", action: .paste)
                }

                Divider()

                HStack(spacing: 12) {
                    Text("同步")
                        .frame(width: 52, alignment: .leading)
                    Button("−0.5") { adjustOffset(-0.5) }
                        .buttonStyle(.bordered)
                    Text(String(format: "%+.1f 秒", subtitleOffset))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 70)
                    Button("+0.5") { adjustOffset(0.5) }
                        .buttonStyle(.bordered)
                    Spacer()
                }

                VStack(spacing: 12) {
                    HStack {
                        Text("字号")
                            .frame(width: 52, alignment: .leading)
                        Slider(value: $subtitleSize, in: 18...56, step: 1)
                            .tint(playerAccentColor)
                        Text("\(Int(subtitleSize)) pt")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 52, alignment: .trailing)
                    }

                    HStack {
                        Text("位置")
                            .frame(width: 52, alignment: .leading)
                        Slider(value: $subtitleBottom, in: 8...180, step: 1)
                            .tint(playerAccentColor)
                        Text("\(Int(subtitleBottom)) pt")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 52, alignment: .trailing)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: 520)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(.white.opacity(0.12), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.38), radius: 30, y: 14)
            .padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func subtitleActionButton(
        _ title: String,
        systemImage: String,
        action: PendingSubtitleAction
    ) -> some View {
        Button {
            requestSubtitleAction(action)
        } label: {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
        }
        .buttonStyle(.bordered)
        .tint(.primary)
    }

    private var pasteSubtitleSheet: some View {
        NavigationStack {
            TextEditor(text: $pastedSubtitle)
                .font(.system(.body, design: .monospaced))
                .padding()
                .navigationTitle("粘贴 SRT / VTT")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showingPasteSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("应用") {
                            applySubtitle(pastedSubtitle, label: "文本")
                            showingPasteSheet = false
                        }
                    }
                }
        }
    }

    private var subtitleSearchSheet: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HStack {
                    TextField("关键词", text: $searchQuery)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)

                    Button {
                        Task { await searchOnlineSubtitles() }
                    } label: {
                        if isSearchingSubtitles {
                            ProgressView()
                        } else {
                            Image(systemName: "magnifyingglass")
                        }
                    }
                    .accessibilityLabel("字幕搜索")
                    .frame(minWidth: 44, minHeight: 44)
                    .disabled(isSearchingSubtitles || downloadingSubtitleURL != nil)
                }

                if !subtitleSearchMessage.isEmpty {
                    Text(subtitleSearchMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                HStack {
                    Button("Subtitlecat") {
                        openSubtitlecat()
                    }
                    .frame(maxWidth: .infinity)
                    Button("导入文件") {
                        pendingSubtitleAction = .importFile
                        showingSubtitleSearch = false
                    }
                    .frame(maxWidth: .infinity)
                    Button("粘贴字幕") {
                        pendingSubtitleAction = .paste
                        showingSubtitleSearch = false
                    }
                    .frame(maxWidth: .infinity)
                }

                if onlineSubtitles.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "captions.bubble")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        if !isSearchingSubtitles && subtitleSearchMessage.isEmpty {
                            Text(hasSearchedSubtitles ? "未找到字幕" : "暂无字幕")
                                .font(.headline)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(onlineSubtitles) { item in
                        Button {
                            Task { await loadRemoteSubtitle(item) }
                        } label: {
                            HStack {
                                Text(item.displayName)
                                    .font(.body)
                                Spacer()
                                if downloadingSubtitleURL == item.url {
                                    ProgressView()
                                }
                            }
                        }
                        .disabled(downloadingSubtitleURL != nil || isSearchingSubtitles)
                    }
                }
            }
            .padding()
            .navigationTitle("在线字幕")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showingSubtitleSearch = false }
                }
            }
        }
        .sheet(item: $subtitlecatPage) { page in
            NavigationStack {
                SubtitlecatWebView(url: page.url)
                    .navigationTitle("Subtitlecat")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("关闭") { subtitlecatPage = nil }
                        }
                    }
            }
        }
    }

    private func requestSubtitleAction(_ action: PendingSubtitleAction) {
        pendingSubtitleAction = action
        withAnimation { showingSubtitleSettings = false }

        Task {
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                performPendingSubtitleAction()
            }
        }
    }

    private func performPendingSubtitleAction() {
        guard let action = pendingSubtitleAction else { return }
        pendingSubtitleAction = nil

        switch action {
        case .search:
            showingSubtitleSearch = true
        case .importFile:
            showingSubtitleImporter = true
        case .paste:
            showingPasteSheet = true
        }
    }

    private var subtitleContentTypes: [UTType] {
        [
            .plainText,
            UTType(filenameExtension: "srt"),
            UTType(filenameExtension: "vtt")
        ].compactMap { $0 }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            status = "音频会话设置失败: \(error.localizedDescription)"
        }
    }

    private func configurePlayer() {
        guard let url = URL(string: candidate.url), candidate.kind != .blob else {
            status = "Blob 链接不能离开原网页直接播放，请选择 m3u8/mp4 候选"
            return
        }

        let headers = [
            "User-Agent": candidate.userAgent,
            "Referer": candidate.referer
        ].filter { !$0.value.isEmpty }

        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        currentTime = 0
        duration = 0
        seekableRanges = []
        isScrubbing = false
        isDraggingSeek = false
        item.audioTimePitchAlgorithm = .spectral
        itemObservations.forEach { $0.invalidate() }
        itemObservations = [
            item.observe(\.status, options: [.initial, .new]) { observedItem, _ in
                Task { @MainActor in
                    guard player.currentItem === observedItem else { return }
                    refreshPlaybackTimeline()
                    if observedItem.status == .failed {
                        status = observedItem.error?.localizedDescription ?? "视频加载失败"
                        wantsPlayback = false
                        isPlaying = false
                        setHoldingSpeed(false)
                        showControls = true
                        hideControlsTask?.cancel()
                    }
                }
            },
            item.observe(\.duration, options: [.new]) { observedItem, _ in
                Task { @MainActor in
                    guard player.currentItem === observedItem else { return }
                    refreshPlaybackTimeline()
                }
            },
            item.observe(\.seekableTimeRanges, options: [.new]) { observedItem, _ in
                Task { @MainActor in
                    guard player.currentItem === observedItem else { return }
                    refreshPlaybackTimeline()
                }
            }
        ]
        player.replaceCurrentItem(with: item)
        player.volume = 1
        player.isMuted = false
        wantsPlayback = true
        applyPlaybackRate()
        isPlaying = true
    }

    private func refreshPlaybackTimeline() {
        guard let item = player.currentItem else { return }
        let itemDuration = item.duration.seconds
        duration = itemDuration.isFinite && itemDuration > 0 ? itemDuration : 0
        seekableRanges = item.seekableTimeRanges.compactMap { value in
            let range = value.timeRangeValue
            let start = range.start.seconds
            let end = CMTimeRangeGetEnd(range).seconds
            guard start.isFinite, end.isFinite, start >= 0, end > start else { return nil }
            return start...end
        }
    }

    private func addTimeObserver() {
        guard timeObserver == nil else { return }
        let interval = CMTime(seconds: 0.2, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            refreshPlaybackTimeline()
            if !isScrubbing {
                currentTime = time.seconds.isFinite ? time.seconds : 0
            }
            let wasPlaying = isPlaying
            isPlaying = player.timeControlStatus == .playing
            if isPlaying && !wasPlaying {
                scheduleControlsAutoHide()
            }
            updateSubtitle(at: currentTime)
        }
    }

    private func rateLabel(_ rate: Float) -> String {
        "\(rate.formatted(.number.precision(.fractionLength(0...2))))×"
    }

    private func applyPlaybackRate() {
        player.defaultRate = effectivePlaybackRate
        if wantsPlayback && !isScrubbing { player.play() }
    }

    private func setHoldingSpeed(_ holding: Bool) {
        guard holding != isHoldingSpeed else { return }
        if holding {
            guard wantsPlayback, !isScrubbing, !showingSubtitleSettings,
                  player.currentItem?.status == .readyToPlay else { return }
            hideControlsTask?.cancel()
        }
        isHoldingSpeed = holding
        applyPlaybackRate()
        if !holding { scheduleControlsAutoHide() }
    }

    private func togglePlay() {
        if wantsPlayback {
            wantsPlayback = false
            setHoldingSpeed(false)
            player.pause()
            isPlaying = false
            showControls = true
            hideControlsTask?.cancel()
        } else {
            wantsPlayback = true
            if duration > 0, currentTime >= duration - 0.1, let range = playbackRange {
                seek(to: range.lowerBound)
            } else {
                applyPlaybackRate()
            }
            scheduleControlsAutoHide()
        }
    }

    private func clampedPlaybackTime(_ seconds: Double) -> Double {
        guard let range = playbackRange else { return 0 }
        return min(range.upperBound, max(range.lowerBound, seconds.isFinite ? seconds : range.lowerBound))
    }

    private func beginScrubbing() {
        guard playbackRange != nil, !isDraggingSeek else { return }
        seekRevision += 1
        isDraggingSeek = true
        isScrubbing = true
        player.pause()
        showControls = true
        hideControlsTask?.cancel()
    }

    private func previewSeek(_ seconds: Double) {
        guard playbackRange != nil else { return }
        beginScrubbing()
        currentTime = clampedPlaybackTime(seconds)
        updateSubtitle(at: currentTime)
    }

    private func seek(to seconds: Double) {
        guard playbackRange != nil, let item = player.currentItem, item.status == .readyToPlay else {
            isDraggingSeek = false
            isScrubbing = false
            applyPlaybackRate()
            scheduleControlsAutoHide()
            return
        }
        let target = clampedPlaybackTime(seconds)
        currentTime = target
        updateSubtitle(at: target)
        isDraggingSeek = false
        isScrubbing = true
        player.pause()
        hideControlsTask?.cancel()
        seekRevision += 1
        let revision = seekRevision
        let tolerance = CMTime(seconds: 0.1, preferredTimescale: 600)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600),
                    toleranceBefore: tolerance, toleranceAfter: tolerance) { _ in
            Task { @MainActor in
                guard revision == seekRevision, player.currentItem === item else { return }
                isScrubbing = false
                let actualTime = player.currentTime().seconds
                if actualTime.isFinite { currentTime = actualTime }
                updateSubtitle(at: currentTime)
                applyPlaybackRate()
                scheduleControlsAutoHide()
            }
        }
    }

    private func seekBy(_ delta: TimeInterval) {
        seek(to: currentTime + delta)
    }

    private func handleDoubleTap(_ direction: PlayerSeekDirection) {
        guard playbackRange != nil else { return }
        let delta: TimeInterval = direction == .forward ? 15 : -15
        seekBy(delta)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()

        seekFeedbackTask?.cancel()
        seekFeedbackText = direction == .forward ? "快进 15 秒" : "后退 15 秒"
        seekFeedbackIcon = direction == .forward ? "goforward.15" : "gobackward.15"
        withAnimation(.easeOut(duration: 0.16)) {
            showSeekFeedback = true
        }

        seekFeedbackTask = Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeIn(duration: 0.16)) {
                    showSeekFeedback = false
                }
            }
        }
    }

    private func handlePan(_ event: PlayerPanEvent) {
        switch event {
        case .began:
            guard playbackRange != nil else { return }
            seekFeedbackTask?.cancel()
            panStartTime = currentTime
            beginScrubbing()
            seekFeedbackIcon = "arrow.left.and.right"
            seekFeedbackText = formatTime(currentTime)
            showSeekFeedback = true

        case .changed(let progress):
            guard let range = playbackRange else { return }
            let seekRange = min(max((range.upperBound - range.lowerBound) * 0.1, 60), 600)
            previewSeek(panStartTime + Double(progress) * seekRange)
            seekFeedbackText = "\(formatTime(currentTime)) / \(formatTime(range.upperBound))"

        case .ended:
            guard isScrubbing else { return }
            seek(to: currentTime)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            hideSeekFeedbackAfterDelay()

        case .cancelled:
            guard isScrubbing else { return }
            currentTime = panStartTime
            isDraggingSeek = false
            isScrubbing = false
            updateSubtitle(at: currentTime)
            applyPlaybackRate()
            scheduleControlsAutoHide()
            hideSeekFeedbackAfterDelay()
        }
    }

    private func hideSeekFeedbackAfterDelay() {
        seekFeedbackTask?.cancel()
        seekFeedbackTask = Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeIn(duration: 0.16)) {
                    showSeekFeedback = false
                }
            }
        }
    }

    private func handleVerticalPan(_ event: PlayerVerticalPanEvent) {
        switch event {
        case .began(let control):
            seekFeedbackTask?.cancel()
            brightnessStart = UIScreen.main.brightness
            volumeStart = systemVolume.volume
            seekFeedbackIcon = control == .brightness ? "sun.max.fill" : "speaker.wave.2.fill"
            let value = control == .brightness ? brightnessStart : Double(volumeStart)
            seekFeedbackText = "\(Int((value * 100).rounded()))%"
            showSeekFeedback = true

        case .changed(let control, let progress):
            let value: Double
            switch control {
            case .brightness:
                value = min(1, max(0, brightnessStart + Double(progress)))
                UIScreen.main.brightness = value
            case .volume:
                value = Double(systemVolume.setVolume(volumeStart + Float(progress)))
            }
            seekFeedbackText = "\(Int((value * 100).rounded()))%"

        case .ended, .cancelled:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            hideSeekFeedbackAfterDelay()
        }
    }

    private func updateSubtitle(at seconds: TimeInterval) {
        subtitleText = subtitleTimeline.textAt(positionMs: SubtitleParser.milliseconds(seconds))
    }

    private func importSubtitle(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }

            let data = try Data(contentsOf: url)
            let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .unicode)
                ?? String(data: data, encoding: .shiftJIS)
                ?? ""
            applySubtitle(text, label: url.lastPathComponent)
        } catch {
            status = "字幕导入失败: \(error.localizedDescription)"
        }
    }

    private func loadSharedSubtitles(_ text: String) -> [SubtitleCue] {
        subtitleTimeline.load(text: text, offsetMs: SubtitleParser.milliseconds(subtitleOffset)).map {
            SubtitleCue(start: Double($0.startMs) / 1000, end: Double($0.endMs) / 1000, text: $0.text)
        }
    }

    private func applySubtitle(_ text: String, label: String) {
        cues = loadSharedSubtitles(text)
        pastedSubtitle = text
        status = cues.isEmpty ? "没有解析到有效字幕" : "已载入 \(cues.count) 条字幕 (\(label))"
    }

    private func adjustOffset(_ delta: TimeInterval) {
        subtitleOffset += delta
        if !pastedSubtitle.isEmpty {
            cues = loadSharedSubtitles(pastedSubtitle)
        }
        status = String(format: "字幕偏移 %.1fs", subtitleOffset)
    }

    private func adjustSubtitleBottom(_ delta: Double) {
        subtitleBottom = clampedSubtitleBottom(subtitleBottom + delta)
        status = "字幕底距 \(Int(subtitleBottom))pt"
    }

    private func clampedSubtitleBottom(_ value: Double) -> Double {
        min(180, max(8, value))
    }

    private func toggleControlsVisibility() {
        showControls.toggle()
        if showControls {
            scheduleControlsAutoHide()
        } else {
            hideControlsTask?.cancel()
        }
    }

    private func keepControlsVisibleForInteraction() {
        showControls = true
        scheduleControlsAutoHide()
    }

    private func scheduleControlsAutoHide() {
        hideControlsTask?.cancel()

        guard !isScrubbing, !isHoldingSpeed, !showingSubtitleSettings else { return }
        guard player.timeControlStatus == .playing || isPlaying else {
            showControls = true
            return
        }

        hideControlsTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard !showingPasteSheet,
                      !showingSubtitleSearch,
                      !showingSubtitleImporter,
                      !showingSubtitleSettings,
                      !isScrubbing,
                      !isHoldingSpeed,
                      subtitlecatPage == nil,
                      player.timeControlStatus == .playing else {
                    return
                }
                showControls = false
            }
        }
    }

    private func searchOnlineSubtitles() async {
        guard !isSearchingSubtitles, downloadingSubtitleURL == nil else { return }
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            subtitleSearchMessage = "请输入番号或关键词"
            return
        }

        isSearchingSubtitles = true
        hasSearchedSubtitles = true
        subtitleSearchMessage = ""
        defer { isSearchingSubtitles = false }

        do {
            var components = URLComponents(string: "https://api-shoulei-ssl.xunlei.com/oracle/subtitle")
            components?.queryItems = [URLQueryItem(name: "name", value: query)]
            guard let url = components?.url else { throw URLError(.badURL) }

            var request = URLRequest(url: url)
            request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }

            let decoded = try JSONDecoder().decode(RemoteSubtitleSearchResponse.self, from: data)
            onlineSubtitles = (decoded.data ?? [])
                .filter { $0.url.lowercased().contains(".srt") || $0.url.lowercased().contains(".vtt") }
        } catch {
            subtitleSearchMessage = "字幕搜索失败: \(error.localizedDescription)"
        }
    }

    private func loadRemoteSubtitle(_ item: OnlineSubtitle) async {
        guard downloadingSubtitleURL == nil, !isSearchingSubtitles else { return }
        guard let url = URL(string: item.url) else {
            subtitleSearchMessage = "字幕链接无效"
            return
        }
        downloadingSubtitleURL = item.url
        subtitleSearchMessage = ""
        defer { downloadingSubtitleURL = nil }

        do {
            var request = URLRequest(url: url)
            request.setValue("text/plain,*/*", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }

            let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .unicode)
                ?? String(data: data, encoding: .shiftJIS)
                ?? ""
            applySubtitle(text, label: item.name)
            showingSubtitleSearch = false
        } catch {
            subtitleSearchMessage = "字幕下载失败: \(error.localizedDescription)"
        }
    }

    private func openSubtitlecat() {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        if let url = URL(string: "https://subtitlecat.com/index.php?search=\(encoded)") {
            subtitlecatPage = WebSheetPage(url: url)
        }
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "00:00" }
        let total = Int(seconds.rounded(.down))
        let s = total % 60
        let m = (total / 60) % 60
        let h = total / 3600
        if h > 0 {
            return String(format: "%02d:%02d:%02d", h, m, s)
        }
        return String(format: "%02d:%02d", m, s)
    }
}

struct PlayerProgressBar: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    let accentColor: Color
    let onEditingChanged: (Bool) -> Void
    @Environment(\.isEnabled) private var isEnabled
    @GestureState private var isDragging = false
    @State private var isEditing = false

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.3))
                Capsule()
                    .fill(accentColor)
                    .frame(width: geometry.size.width * progressFraction)
            }
            .frame(height: isEditing ? 6 : 3)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($isDragging) { _, dragging, _ in dragging = true }
                    .onChanged { gesture in
                        guard isEnabled, geometry.size.width > 0 else { return }
                        if !isEditing {
                            isEditing = true
                            onEditingChanged(true)
                        }
                        let fraction = min(1, max(0, gesture.location.x / geometry.size.width))
                        value = range.lowerBound + Double(fraction) * (range.upperBound - range.lowerBound)
                    }
                    .onEnded { _ in finishEditing() },
                including: isEnabled ? .all : .none
            )
        }
        .frame(height: 44)
        .animation(.easeOut(duration: 0.15), value: isEditing)
        .opacity(isEnabled ? 1 : 0.4)
        .onChange(of: isDragging) { dragging in
            if !dragging { finishEditing() }
        }
        .onChange(of: isEnabled) { enabled in
            if !enabled { finishEditing() }
        }
        .onDisappear { finishEditing() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("播放进度")
        .accessibilityValue("\(Int(progressFraction * 100))%")
        .accessibilityAdjustableAction { direction in
            guard isEnabled else { return }
            let step = (range.upperBound - range.lowerBound) * 0.05
            onEditingChanged(true)
            switch direction {
            case .increment:
                value = min(range.upperBound, value + step)
            case .decrement:
                value = max(range.lowerBound, value - step)
            @unknown default:
                break
            }
            onEditingChanged(false)
        }
    }

    private func finishEditing() {
        guard isEditing else { return }
        isEditing = false
        onEditingChanged(false)
    }

    private var progressFraction: CGFloat {
        let span = range.upperBound - range.lowerBound
        guard span > 0 else { return 0 }
        return CGFloat(min(1, max(0, (value - range.lowerBound) / span)))
    }

}

enum PlayerSeekDirection {
    case backward
    case forward
}

enum PlayerPanEvent {
    case began
    case changed(CGFloat)
    case ended
    case cancelled
}

enum PlayerVerticalControl {
    case brightness
    case volume
}

enum PlayerVerticalPanEvent {
    case began(PlayerVerticalControl)
    case changed(PlayerVerticalControl, CGFloat)
    case ended
    case cancelled
}

@MainActor
final class PlayerSystemVolumeControl {
    // Keep the native control mounted for its system-volume connection while
    // the player presents its existing gesture feedback instead of a slider.
    let view = MPVolumeView(frame: CGRect(x: -1000, y: -1000, width: 200, height: 44))

    init() {
        view.isUserInteractionEnabled = false
        view.accessibilityElementsHidden = true
    }

    var volume: Float { AVAudioSession.sharedInstance().outputVolume }

    @discardableResult
    func setVolume(_ value: Float) -> Float {
        guard view.window != nil,
              let slider = view.subviews.compactMap({ $0 as? UISlider }).first else {
            return volume
        }
        slider.setValue(min(1, max(0, value)), animated: false)
        slider.sendActions(for: .valueChanged)
        return slider.value
    }
}

@MainActor
final class PlayerPictureInPicture: NSObject, ObservableObject, AVPictureInPictureControllerDelegate {
    @Published private(set) var isPossible = false
    @Published private(set) var isPresenting = false
    @Published private(set) var errorMessage: String?

    private var controller: AVPictureInPictureController?
    private var possibilityObservation: NSKeyValueObservation?
    private var isRestoringPlayer = false

    func attach(to layer: AVPlayerLayer) {
        guard AVPictureInPictureController.isPictureInPictureSupported(),
              let controller = AVPictureInPictureController(playerLayer: layer) else { return }

        self.controller = controller
        controller.delegate = self
        possibilityObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) {
            [weak self] observedController, _ in
            let controllerID = ObjectIdentifier(observedController)
            DispatchQueue.main.async {
                guard let self, let controller = self.controller, ObjectIdentifier(controller) == controllerID else { return }
                self.isPossible = controller.isPictureInPicturePossible
            }
        }
    }

    func detach(from layer: AVPlayerLayer) {
        guard let controller, controller.playerLayer === layer else { return }
        stop()
        controller.delegate = nil
        possibilityObservation?.invalidate()
        possibilityObservation = nil
        self.controller = nil
    }

    func setAllowsAutomaticStart(_ allowsAutomaticStart: Bool) {
        if let controller, controller.canStartPictureInPictureAutomaticallyFromInline != allowsAutomaticStart {
            controller.canStartPictureInPictureAutomaticallyFromInline = allowsAutomaticStart
        }
    }

    func start() {
        guard let controller, controller.isPictureInPicturePossible, !isPresenting else { return }
        errorMessage = nil
        controller.startPictureInPicture()
    }

    func stop() {
        if let controller, controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        }
    }

    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        let controllerID = ObjectIdentifier(controller)
        DispatchQueue.main.async { [weak self] in
            guard let self, self.controller.map(ObjectIdentifier.init) == controllerID else { return }
            self.isRestoringPlayer = false
            self.errorMessage = nil
            self.isPresenting = true
        }
    }

    nonisolated func pictureInPictureController(
        _ controller: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: Error
    ) {
        let controllerID = ObjectIdentifier(controller)
        let message = "画中画启动失败：\(error.localizedDescription)"
        DispatchQueue.main.async { [weak self] in
            guard let self, self.controller.map(ObjectIdentifier.init) == controllerID else { return }
            self.isPresenting = false
            self.errorMessage = message
        }
    }

    nonisolated func pictureInPictureController(
        _ controller: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping @Sendable (Bool) -> Void
    ) {
        // The fullscreen player stays presented, including its current playback item.
        let controllerID = ObjectIdentifier(controller)
        DispatchQueue.main.async { [weak self] in
            guard let self, let controller = self.controller, ObjectIdentifier(controller) == controllerID else {
                completionHandler(false)
                return
            }
            self.isRestoringPlayer = controller.playerLayer.player?.currentItem != nil
            completionHandler(self.isRestoringPlayer)
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        let controllerID = ObjectIdentifier(controller)
        DispatchQueue.main.async { [weak self] in
            guard let self, let controller = self.controller, ObjectIdentifier(controller) == controllerID else { return }
            if !self.isRestoringPlayer && UIApplication.shared.applicationState == .background {
                controller.playerLayer.player?.pause()
            }
            self.isPresenting = false
        }
    }
}

struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    let pictureInPicture: PlayerPictureInPicture
    let allowsAutomaticPictureInPicture: Bool
    let systemVolume: PlayerSystemVolumeControl
    let onSingleTap: () -> Void
    let onDoubleTap: (PlayerSeekDirection) -> Void
    let onPan: (PlayerPanEvent) -> Void
    let onVerticalPan: (PlayerVerticalPanEvent) -> Void
    let onHoldChanged: (Bool) -> Void
    let canSeek: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(
            pictureInPicture: pictureInPicture,
            onSingleTap: onSingleTap,
            onDoubleTap: onDoubleTap,
            onPan: onPan,
            onVerticalPan: onVerticalPan,
            onHoldChanged: onHoldChanged,
            canSeek: canSeek
        )
    }

    func makeUIView(context: Context) -> PlayerLayerContainerView {
        let view = PlayerLayerContainerView()
        view.playerLayer.player = player
        pictureInPicture.attach(to: view.playerLayer)
        view.addSubview(systemVolume.view)

        let singleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleSingleTap))
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        singleTap.require(toFail: doubleTap)
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePan(_:)))
        pan.name = "horizontalSeek"
        pan.delegate = context.coordinator
        let verticalPan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleVerticalPan(_:)))
        verticalPan.name = "verticalControl"
        verticalPan.delegate = context.coordinator
        let hold = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleHold(_:)))
        hold.minimumPressDuration = 0.4
        hold.allowableMovement = 15
        singleTap.require(toFail: hold)
        view.addGestureRecognizer(singleTap)
        view.addGestureRecognizer(doubleTap)
        view.addGestureRecognizer(pan)
        view.addGestureRecognizer(verticalPan)
        view.addGestureRecognizer(hold)
        return view
    }

    func updateUIView(_ uiView: PlayerLayerContainerView, context: Context) {
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
        pictureInPicture.setAllowsAutomaticStart(allowsAutomaticPictureInPicture)
        context.coordinator.onSingleTap = onSingleTap
        context.coordinator.onDoubleTap = onDoubleTap
        context.coordinator.onPan = onPan
        context.coordinator.onVerticalPan = onVerticalPan
        context.coordinator.onHoldChanged = onHoldChanged
        context.coordinator.canSeek = canSeek
    }

    static func dismantleUIView(_ view: PlayerLayerContainerView, coordinator: Coordinator) {
        coordinator.pictureInPicture.detach(from: view.playerLayer)
        coordinator.onHoldChanged(false)
        view.playerLayer.player = nil
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let pictureInPicture: PlayerPictureInPicture
        var onSingleTap: () -> Void
        var onDoubleTap: (PlayerSeekDirection) -> Void
        var onPan: (PlayerPanEvent) -> Void
        var onVerticalPan: (PlayerVerticalPanEvent) -> Void
        var onHoldChanged: (Bool) -> Void
        var canSeek: Bool
        private var activeVerticalControl: PlayerVerticalControl = .brightness

        init(
            pictureInPicture: PlayerPictureInPicture,
            onSingleTap: @escaping () -> Void,
            onDoubleTap: @escaping (PlayerSeekDirection) -> Void,
            onPan: @escaping (PlayerPanEvent) -> Void,
            onVerticalPan: @escaping (PlayerVerticalPanEvent) -> Void,
            onHoldChanged: @escaping (Bool) -> Void,
            canSeek: Bool
        ) {
            self.pictureInPicture = pictureInPicture
            self.onSingleTap = onSingleTap
            self.onDoubleTap = onDoubleTap
            self.onPan = onPan
            self.onVerticalPan = onVerticalPan
            self.onHoldChanged = onHoldChanged
            self.canSeek = canSeek
        }

        @objc func handleSingleTap() {
            onSingleTap()
        }

        @objc func handleHold(_ recognizer: UILongPressGestureRecognizer) {
            switch recognizer.state {
            case .began:
                onHoldChanged(true)
            case .ended, .cancelled, .failed:
                onHoldChanged(false)
            default:
                break
            }
        }

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let direction: PlayerSeekDirection = recognizer.location(in: view).x < view.bounds.midX ? .backward : .forward
            onDoubleTap(direction)
        }

        @objc func handlePan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view, view.bounds.width > 0 else { return }
            let translation = recognizer.translation(in: view)

            switch recognizer.state {
            case .began:
                onPan(.began)
            case .changed:
                onPan(.changed(translation.x / view.bounds.width))
            case .ended:
                onPan(.ended)
            case .cancelled, .failed:
                onPan(.cancelled)
            default:
                break
            }
        }

        @objc func handleVerticalPan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view, view.bounds.height > 0 else { return }
            let translation = recognizer.translation(in: view)

            switch recognizer.state {
            case .began:
                activeVerticalControl = recognizer.location(in: view).x < view.bounds.midX ? .brightness : .volume
                onVerticalPan(.began(activeVerticalControl))
            case .changed:
                onVerticalPan(.changed(activeVerticalControl, -translation.y / view.bounds.height))
            case .ended:
                onVerticalPan(.ended)
            case .cancelled, .failed:
                onVerticalPan(.cancelled)
            default:
                break
            }
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
            let velocity = pan.velocity(in: pan.view)
            if pan.name == "verticalControl" {
                return abs(velocity.y) > abs(velocity.x) * 1.2
            }
            return canSeek && abs(velocity.x) > abs(velocity.y) * 1.2
        }
    }
}

final class PlayerLayerContainerView: UIView {
    override class var layerClass: AnyClass {
        AVPlayerLayer.self
    }

    var playerLayer: AVPlayerLayer {
        layer as! AVPlayerLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

struct SubtitlecatWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if webView.url != url {
            webView.load(URLRequest(url: url))
        }
    }
}

struct WebSheetPage: Identifiable {
    let id = UUID()
    let url: URL
}
