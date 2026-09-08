import { useState, useRef, useEffect } from "react";
import axios from "axios";
import {
  WavAudioRecorder,
  renderStudioVisualizer,
  getDecibelLevel,
  formatBytes,
} from "../utils/audioUtils";
import { API_BASE } from "../config/api";
import "./SongRecognizer.css";

export default function SongRecognizer() {
  const [inputMode, setInputMode] = useState("mic"); // "mic" | "file"
  const [visualizerMode, setVisualizerMode] = useState("spectrum"); // "spectrum" | "oscilloscope"
  const [isListening, setIsListening] = useState(false);
  const [listenSeconds, setListenSeconds] = useState(0);
  const [dbLevel, setDbLevel] = useState(-60);

  const [selectedFile, setSelectedFile] = useState(null);
  const [audioPreviewUrl, setAudioPreviewUrl] = useState(null);

  const [status, setStatus] = useState("idle"); // "idle" | "analyzing" | "matched" | "no_match" | "error"
  const [matchResult, setMatchResult] = useState(null);
  const [errorMessage, setErrorMessage] = useState("");
  const [isLoadingDemo, setIsLoadingDemo] = useState(false);

  const recorderRef = useRef(null);
  const canvasRef = useRef(null);
  const timerRef = useRef(null);
  const dbIntervalRef = useRef(null);
  const cancelVisualizerRef = useRef(null);

  // Clean up resources on unmount
  useEffect(() => {
    return () => {
      if (audioPreviewUrl) URL.revokeObjectURL(audioPreviewUrl);
      if (timerRef.current) clearInterval(timerRef.current);
      if (dbIntervalRef.current) clearInterval(dbIntervalRef.current);
      if (cancelVisualizerRef.current) cancelVisualizerRef.current();
    };
  }, [audioPreviewUrl]);

  // Start Microphone Capture
  const startListening = async () => {
    try {
      setErrorMessage("");
      setStatus("idle");
      setMatchResult(null);

      const recorder = new WavAudioRecorder();
      await recorder.start();
      recorderRef.current = recorder;
      setIsListening(true);
      setListenSeconds(0);

      // Listening timer
      timerRef.current = setInterval(() => {
        setListenSeconds((prev) => {
          if (prev >= 14) {
            stopListeningAndIdentify();
            return prev;
          }
          return prev + 1;
        });
      }, 1000);

      // Live dB meter polling
      dbIntervalRef.current = setInterval(() => {
        if (recorder.getAnalyser()) {
          const { db } = getDecibelLevel(recorder.getAnalyser());
          setDbLevel(db);
        }
      }, 80);

      // Start studio visualizer
      setTimeout(() => {
        if (canvasRef.current && recorder.getAnalyser()) {
          cancelVisualizerRef.current = renderStudioVisualizer(
            canvasRef.current,
            recorder.getAnalyser(),
            visualizerMode
          );
        }
      }, 80);
    } catch (err) {
      console.error("Microphone initialization error:", err);
      setErrorMessage(
        "Microphone input unavailable. Please grant microphone access in browser settings."
      );
      setStatus("error");
    }
  };

  // Stop Microphone and Submit
  const stopListeningAndIdentify = () => {
    if (!recorderRef.current || !isListening) return;

    if (timerRef.current) clearInterval(timerRef.current);
    if (dbIntervalRef.current) clearInterval(dbIntervalRef.current);
    if (cancelVisualizerRef.current) cancelVisualizerRef.current();

    setIsListening(false);
    setDbLevel(-60);

    const wavFile = recorderRef.current.stop();
    recorderRef.current = null;

    if (wavFile) {
      submitAudioForIdentification(wavFile);
    }
  };

  // Switch Visualizer Mode on the fly
  const toggleVisualizerMode = (mode) => {
    setVisualizerMode(mode);
    if (isListening && recorderRef.current && canvasRef.current) {
      if (cancelVisualizerRef.current) cancelVisualizerRef.current();
      cancelVisualizerRef.current = renderStudioVisualizer(
        canvasRef.current,
        recorderRef.current.getAnalyser(),
        mode
      );
    }
  };

  // Submit audio file to Spring Boot recognition API
  const submitAudioForIdentification = async (file) => {
    setStatus("analyzing");
    setErrorMessage("");

    const formData = new FormData();
    formData.append("file", file);
    formData.append("command", "getFingerprint");

    try {
      const response = await axios.post(
        `${API_BASE}/api/recognize`,
        formData,
        {
          headers: { "Content-Type": "multipart/form-data" },
        }
      );

      const data = response.data;
      if (data && data.matched && data.song) {
        setMatchResult({
          ...data.song,
          confidence: data.confidence,
          message: data.message,
        });
        setStatus("matched");
      } else {
        setStatus("no_match");
        setErrorMessage(
          data.message ||
            "No coherent acoustic alignment found in the database. The audio may not be indexed yet or is too noisy."
        );
      }
    } catch (err) {
      console.error("Recognition request failure:", err);
      setStatus("error");
      setErrorMessage(
        err.response?.data?.message ||
          "Failed to communicate with identification daemon. Verify backend server is online at :8080."
      );
    }
  };

  const DEMO_CLIPS = [
    {
      id: "starboy",
      title: "Starboy",
      artist: "The Weeknd ft. Daft Punk",
      path: "/demo_sample.wav",
      genre: "Electro-Pop",
      duration: "10s clip",
      accent: "#ff4757"
    },
    {
      id: "afterdark",
      title: "After Dark",
      artist: "Mr.Kitty",
      path: "/demo_afterdark.wav",
      genre: "Synthwave",
      duration: "10s clip",
      accent: "#9b59b6"
    },
    {
      id: "countingstars",
      title: "Counting Stars",
      artist: "OneRepublic",
      path: "/demo_countingstars.wav",
      genre: "Pop / Indie",
      duration: "10s clip",
      accent: "#2ecc71"
    },
    {
      id: "alorsondanse",
      title: "Alors on danse",
      artist: "Stromae",
      path: "/demo_alorsondanse.wav",
      genre: "French Electro",
      duration: "10s clip",
      accent: "#00d2d3"
    }
  ];

  // One-Click Demo Sample Loader
  const loadAndIdentifyDemoTrack = async (clip = DEMO_CLIPS[0]) => {
    setIsLoadingDemo(true);
    resetAll();
    setInputMode("file");
    try {
      const response = await fetch(clip.path);
      if (!response.ok) throw new Error("Reference audio file not found: " + clip.path);
      const blob = await response.blob();
      const demoFile = new File([blob], clip.path.split("/").pop(), { type: "audio/wav" });

      setSelectedFile(demoFile);
      setAudioPreviewUrl(URL.createObjectURL(demoFile));
      setIsLoadingDemo(false);

      // Trigger automatic identification
      submitAudioForIdentification(demoFile);
    } catch (err) {
      console.error("Could not load demo track:", err);
      setIsLoadingDemo(false);
      setErrorMessage("Could not load audio sample from " + clip.path);
      setStatus("error");
    }
  };

  const handleFileSelect = (file) => {
    if (!file) return;
    if (audioPreviewUrl) URL.revokeObjectURL(audioPreviewUrl);

    setSelectedFile(file);
    setAudioPreviewUrl(URL.createObjectURL(file));
    setStatus("idle");
    setMatchResult(null);
    setErrorMessage("");
  };

  const resetAll = () => {
    if (timerRef.current) clearInterval(timerRef.current);
    if (dbIntervalRef.current) clearInterval(dbIntervalRef.current);
    if (cancelVisualizerRef.current) cancelVisualizerRef.current();
    if (isListening && recorderRef.current) {
      recorderRef.current.stop();
    }
    setIsListening(false);
    setDbLevel(-60);
    setStatus("idle");
    setMatchResult(null);
    setErrorMessage("");
    setSelectedFile(null);
    if (audioPreviewUrl) URL.revokeObjectURL(audioPreviewUrl);
    setAudioPreviewUrl(null);
  };

  const formatTimer = (sec) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  };

  // Convert dB (-60 to 0) to meter percentage (0 to 100%)
  const dbPercentage = Math.min(100, Math.max(0, ((dbLevel + 60) / 60) * 100));

  const getYouTubeThumbnail = (url) => {
    if (!url) return null;
    const regExp = /^.*(youtu.be\/|v\/|u\/\w\/|embed\/|watch\?v=|&v=)([^#&?]*).*/;
    const match = url.match(regExp);
    return (match && match[2].length === 11) ? `https://img.youtube.com/vi/${match[2]}/hqdefault.jpg` : null;
  };

  return (
    <div className="recognizer-station animate-fade-in">
      {/* Studio Header Bar */}
      <div className="station-header">
        <div className="station-title-group">
          <h1>Acoustic Fingerprint Identifier</h1>
          <p className="station-lead">
            Real-time spectral constellation matcher powered by C++ FFTW3 and MongoDB time-alignment scoring.
          </p>
        </div>

        {/* Quick Demo Track Action */}
        <button
          type="button"
          className="btn btn-demo"
          onClick={() => loadAndIdentifyDemoTrack(DEMO_CLIPS[0])}
          disabled={status === "analyzing" || isListening || isLoadingDemo}
          title="Instantly tests the engine with the bundled reference audio (Starboy - The Weeknd)"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
            <polygon points="5 3 19 12 5 21 5 3"/>
          </svg>
          {isLoadingDemo ? "Loading Demo..." : "Quick Test (Starboy)"}
        </button>
      </div>

      {/* Hardware Telemetry Bar */}
      <div className="studio-telemetry-strip mono">
        <div className="telemetry-cell">
          <span className="telemetry-label">SAMPLING RATE</span>
          <span className="telemetry-val">16,000 Hz</span>
        </div>
        <div className="telemetry-cell">
          <span className="telemetry-label">WINDOWING</span>
          <span className="telemetry-val">Hamming 2048</span>
        </div>
        <div className="telemetry-cell">
          <span className="telemetry-label">PEAK TARGET ZONE</span>
          <span className="telemetry-val">3×3 Stencil</span>
        </div>
        <div className="telemetry-cell">
          <span className="telemetry-label">SIGNAL LEVEL</span>
          <span className={`telemetry-val ${dbLevel > -12 ? "hot" : ""}`}>
            {isListening ? `${dbLevel} dBFS` : "STANDBY"}
          </span>
        </div>
      </div>

      {/* Quick Test Bench: Try Real Master Clips */}
      <div className="demo-bench-container">
        <div className="demo-bench-header">
          <span className="bench-title">Interactive Reference Clips</span>
          <span className="bench-subtitle mono">Select a sample to hear and identify instantly</span>
        </div>
        <div className="demo-bench-grid">
          {DEMO_CLIPS.map((clip) => (
            <div
              key={clip.id}
              className={`demo-card ${selectedFile?.name?.includes(clip.id) ? "selected" : ""}`}
              style={{ "--card-accent": clip.accent }}
            >
              <div className="demo-card-top">
                <span className="demo-genre mono">{clip.genre}</span>
                <span className="demo-dur mono">{clip.duration}</span>
              </div>
              <div className="demo-card-body">
                <div className="demo-title">{clip.title}</div>
                <div className="demo-artist">{clip.artist}</div>
              </div>
              <div className="demo-card-actions">
                <button
                  type="button"
                  className="demo-btn-identify"
                  onClick={() => loadAndIdentifyDemoTrack(clip)}
                  disabled={status === "analyzing" || isListening || isLoadingDemo}
                >
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="currentColor">
                    <polygon points="5 3 19 12 5 21 5 3"/>
                  </svg>
                  Identify
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Mode Selector Tabs */}
      <div className="console-mode-selector">
        <button
          type="button"
          className={`console-tab ${inputMode === "mic" ? "active" : ""}`}
          onClick={() => {
            resetAll();
            setInputMode("mic");
          }}
          disabled={status === "analyzing" || isListening}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
            <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
            <line x1="12" y1="19" x2="12" y2="23"/>
            <line x1="8" y1="23" x2="16" y2="23"/>
          </svg>
          Live Microphone Input
        </button>

        <button
          type="button"
          className={`console-tab ${inputMode === "file" ? "active" : ""}`}
          onClick={() => {
            resetAll();
            setInputMode("file");
          }}
          disabled={status === "analyzing" || isListening}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="12" y1="18" x2="12" y2="12"/>
            <line x1="9" y1="15" x2="15" y2="15"/>
          </svg>
          Audio File / Sample Upload
        </button>
      </div>

      {/* Main Studio Console Deck */}
      <div className="studio-card main-deck">
        {/* =========================================================
            STATE: MATCH SUCCESS
           ========================================================= */}
        {status === "matched" && matchResult && (
          <div className="match-dossier animate-fade-in">
            <div className="dossier-header">
              <div className="dossier-headline">
                <span className="badge-match">ACOUSTIC MATCH CONFIRMED</span>
                <span className="confidence-score mono">
                  Confidence: <strong>{matchResult.confidence?.toLocaleString()}</strong> aligned peak clusters
                </span>
              </div>
            </div>

            <div className="dossier-body">
              {/* Album Sleeve with Real YouTube Cover Art & Rotating Vinyl */}
              <div className="dossier-media">
                <div className="album-sleeve">
                  <div className="sleeve-art">
                    {getYouTubeThumbnail(matchResult.link) ? (
                      <img
                        src={getYouTubeThumbnail(matchResult.link)}
                        alt={matchResult.name}
                        className="sleeve-art-cover"
                      />
                    ) : (
                      <svg viewBox="0 0 24 24" width="48" height="48" fill="rgba(0, 212, 255, 0.5)">
                        <path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/>
                      </svg>
                    )}
                  </div>
                  <div className="vinyl-disc spinning">
                    <div className="vinyl-grooves"></div>
                    <div className="vinyl-center"></div>
                  </div>
                </div>
              </div>

              {/* Track Metadata */}
              <div className="dossier-meta">
                <span className="track-id mono">CATALOG MASTER #{matchResult.id}</span>
                <h2 className="track-title">{matchResult.name}</h2>
                <h3 className="track-artist">{matchResult.artist || "Unknown Artist"}</h3>

                {/* Direct Action Links */}
                <div className="track-external-actions">
                  {matchResult.link && (
                    <a
                      href={matchResult.link}
                      target="_blank"
                      rel="noreferrer"
                      className="btn btn-action-yt"
                    >
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="#ff0000">
                        <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
                      </svg>
                      Watch on YouTube
                    </a>
                  )}

                  <a
                    href={`https://open.spotify.com/search/${encodeURIComponent(
                      `${matchResult.artist} ${matchResult.name}`
                    )}`}
                    target="_blank"
                    rel="noreferrer"
                    className="btn btn-action-spotify"
                  >
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="#1db954">
                      <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"/>
                    </svg>
                    Spotify
                  </a>
                </div>
              </div>
            </div>

            {/* Audio Preview if available */}
            {audioPreviewUrl && (
              <div className="dossier-audio-player">
                <span className="audio-label mono">ACOUSTIC SAMPLE PLAYBACK</span>
                <audio controls src={audioPreviewUrl} className="embedded-audio" />
              </div>
            )}

            <div className="dossier-footer">
              <button type="button" className="btn btn-primary" onClick={resetAll}>
                Identify Another Track
              </button>
            </div>
          </div>
        )}

        {/* =========================================================
            STATE: NO MATCH / ERROR
           ========================================================= */}
        {(status === "no_match" || status === "error") && (
          <div className="no-match-dossier animate-fade-in">
            <div className="status-indicator-box">
              <span className="status-code-pill mono">
                {status === "no_match" ? "STATUS: 404_NO_CORRELATION" : "STATUS: ENGINE_ERROR"}
              </span>
              <h3>{status === "no_match" ? "No Coherent Acoustic Match" : "Processing Fault"}</h3>
              <p className="status-explanation">{errorMessage}</p>

              <div className="status-button-row">
                <button type="button" className="btn btn-primary" onClick={resetAll}>
                  Reset & Try Again
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={loadAndIdentifyDemoTrack}
                >
                  Run Bundled Demo Sample
                </button>
              </div>
            </div>
          </div>
        )}

        {/* =========================================================
            STATE: ANALYZING AUDIO
           ========================================================= */}
        {status === "analyzing" && (
          <div className="analyzing-console animate-fade-in">
            <div className="radar-station">
              <div className="radar-sweep-ring r1"></div>
              <div className="radar-sweep-ring r2"></div>
              <div className="radar-core-hub">
                <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="#00e5ff" strokeWidth="2">
                  <path d="M12 2v20M17 5v14M7 8v8M22 10v4M2 11v2" />
                </svg>
              </div>
            </div>

            <div className="analyzing-telemetry">
              <h3>Processing Audio Constellation...</h3>
              <p className="analyzing-description">
                Executing 1D real-to-complex FFT with Hamming windowing, extracting local spectrogram maxima, and cross-matching hash offsets against MongoDB collection.
              </p>
              <div className="analyzing-steps mono">
                <span>[1] Audio Resample 16kHz</span>
                <span>[2] Fast Fourier Transform</span>
                <span>[3] Peak Anchoring</span>
                <span>[4] Constellation Delta Match</span>
              </div>
            </div>
          </div>
        )}

        {/* =========================================================
            STATE: IDLE / RECORDING / FILE STAGED
           ========================================================= */}
        {status === "idle" && (
          <>
            {/* MODE: MICROPHONE CAPTURE */}
            {inputMode === "mic" && (
              <div className="console-mic-deck">
                {/* Central Audio Actuator Knob */}
                <div className="actuator-station">
                  <div className={`actuator-halo ${isListening ? "active" : ""}`}>
                    <button
                      type="button"
                      className={`actuator-knob ${isListening ? "recording" : ""}`}
                      onClick={isListening ? stopListeningAndIdentify : startListening}
                      title={isListening ? "Stop listening and identify" : "Click to start listening"}
                    >
                      <div className="knob-face">
                        {isListening ? (
                          <div className="knob-stop-glyph"></div>
                        ) : (
                          <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor" strokeWidth="2.5">
                            <path d="M12 2v20M17 5v14M7 8v8M22 10v4M2 11v2" />
                          </svg>
                        )}
                      </div>
                    </button>
                  </div>
                </div>

                {/* Status Indicator */}
                <div className="actuator-label-group">
                  {isListening ? (
                    <div className="listening-banner animate-fade-in">
                      <div className="recording-led-pill">
                        <span className="red-led"></span>
                        <span className="mono">LISTENING [{formatTimer(listenSeconds)} / 00:15]</span>
                      </div>
                      <p className="listening-prompt">
                        Capturing ambient audio signal. Click the center control when ready to identify.
                      </p>
                    </div>
                  ) : (
                    <div className="idle-banner">
                      <h2>Click Center Console to Identify</h2>
                      <p className="idle-prompt">
                        Auto-normalized 16kHz PCM with AGC enabled. Play 5–8 seconds of any cataloged song near your mic.
                      </p>
                    </div>
                  )}
                </div>

                {/* Real-time Visualizer Screen & VU Meter */}
                <div className={`studio-monitor-deck ${isListening ? "active" : "inactive"}`}>
                  <div className="monitor-top-bar">
                    <span className="monitor-label mono">
                      {visualizerMode === "spectrum" ? "REAL-TIME FFT SPECTRUM" : "TIME-DOMAIN OSCILLOSCOPE"}
                    </span>
                    <div className="monitor-controls">
                      <button
                        type="button"
                        className={`monitor-btn ${visualizerMode === "spectrum" ? "active" : ""}`}
                        onClick={() => toggleVisualizerMode("spectrum")}
                      >
                        FFT
                      </button>
                      <button
                        type="button"
                        className={`monitor-btn ${visualizerMode === "oscilloscope" ? "active" : ""}`}
                        onClick={() => toggleVisualizerMode("oscilloscope")}
                      >
                        OSC
                      </button>
                    </div>
                  </div>

                  <canvas
                    ref={canvasRef}
                    width={560}
                    height={80}
                    className="monitor-canvas"
                  ></canvas>

                  {/* Hardware VU Meter */}
                  <div className="vu-meter-bar">
                    <span className="vu-label mono">-60dB</span>
                    <div className="vu-track">
                      <div
                        className="vu-level"
                        style={{ width: `${isListening ? dbPercentage : 0}%` }}
                      ></div>
                    </div>
                    <span className="vu-label mono">0dB</span>
                  </div>
                </div>
              </div>
            )}

            {/* MODE: FILE UPLOAD */}
            {inputMode === "file" && (
              <div className="console-file-deck">
                <div
                  className={`studio-dropzone ${selectedFile ? "staged" : ""}`}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => {
                    e.preventDefault();
                    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
                      handleFileSelect(e.dataTransfer.files[0]);
                    }
                  }}
                >
                  <label htmlFor="file-stage-input" className="dropzone-surface">
                    <div className="dropzone-glyph">
                      <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M9 18V5l12-2v13"/>
                        <circle cx="6" cy="18" r="3"/>
                        <circle cx="18" cy="16" r="3"/>
                      </svg>
                    </div>
                    <div className="dropzone-text-block">
                      <span className="dropzone-primary-text">
                        {selectedFile ? selectedFile.name : "Select or Drop Master Audio File"}
                      </span>
                      <span className="dropzone-secondary-text mono">
                        WAV, MP3, FLAC, OGG supported • Audio downmixed & resampled to 16kHz mono
                      </span>
                    </div>
                  </label>

                  <input
                    id="file-stage-input"
                    type="file"
                    accept="audio/*"
                    className="file-hidden-input"
                    onChange={(e) => {
                      if (e.target.files && e.target.files[0]) {
                        handleFileSelect(e.target.files[0]);
                      }
                    }}
                  />
                </div>

                {/* Staged Audio File Panel */}
                {selectedFile && (
                  <div className="staged-file-deck animate-fade-in">
                    <div className="staged-file-specs">
                      <div className="spec-item">
                        <span className="spec-label mono">FILE NAME</span>
                        <span className="spec-value">{selectedFile.name}</span>
                      </div>
                      <div className="spec-item">
                        <span className="spec-label mono">SIZE</span>
                        <span className="spec-value mono">{formatBytes(selectedFile.size)}</span>
                      </div>
                      <div className="spec-item">
                        <span className="spec-label mono">FORMAT</span>
                        <span className="spec-value mono">
                          {selectedFile.type || "audio/x-raw"}
                        </span>
                      </div>
                    </div>

                    {audioPreviewUrl && (
                      <div className="staged-audio-preview">
                        <audio controls src={audioPreviewUrl} className="preview-audio-element" />
                      </div>
                    )}

                    <div className="staged-action-row">
                      <button
                        type="button"
                        className="btn btn-primary submit-identification-btn"
                        onClick={() => submitAudioForIdentification(selectedFile)}
                      >
                        Execute Acoustic Identification
                      </button>
                      <button
                        type="button"
                        className="btn btn-secondary"
                        onClick={() => {
                          setSelectedFile(null);
                          if (audioPreviewUrl) URL.revokeObjectURL(audioPreviewUrl);
                          setAudioPreviewUrl(null);
                        }}
                      >
                        Eject File
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
