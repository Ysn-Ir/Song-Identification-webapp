import { useState, useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import axios from "axios";
import { formatBytes } from "../utils/audioUtils";
import { API_BASE } from "../config/api";
import "./FileUploader.css";

export default function FileUploader() {
  const [sourceMode, setSourceMode] = useState("files"); // "files" | "youtube"

  // Local File States
  const [stagedFiles, setStagedFiles] = useState([]);
  const [indexingStatus, setIndexingStatus] = useState("idle"); // "idle" | "uploading" | "processing" | "success" | "error"
  const [uploadPercent, setUploadPercent] = useState(0);
  const [indexedResults, setIndexedResults] = useState([]);
  const [failureMsg, setFailureMsg] = useState("");

  // YouTube States
  const [youtubeUrlsText, setYoutubeUrlsText] = useState("");
  const [maxTracks, setMaxTracks] = useState(5);
  const [quickSampleOnly, setQuickSampleOnly] = useState(true);

  // Live SSE Telemetry Logs
  const [logs, setLogs] = useState([
    { time: "--:--:--", level: "SYSTEM", message: "Awaiting audio ingestion input..." }
  ]);
  const [showTerminal, setShowTerminal] = useState(true);
  const terminalEndRef = useRef(null);

  // Subscribe to SSE Logs
  useEffect(() => {
    const sse = new EventSource(`${API_BASE}/api/indexing/logs`);

    sse.addEventListener("log", (event) => {
      try {
        const entry = JSON.parse(event.data);
        setLogs((prev) => [...prev.slice(-150), entry]);
      } catch (err) {
        console.error("SSE parse error", err);
      }
    });

    sse.onerror = () => {
      // Reconnection handled automatically by browser EventSource
    };

    return () => {
      sse.close();
    };
  }, []);

  // Auto-scroll terminal to bottom
  useEffect(() => {
    if (terminalEndRef.current && showTerminal) {
      terminalEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [logs, showTerminal]);

  const handleFilesAdded = (incoming) => {
    if (!incoming || incoming.length === 0) return;

    const parsed = Array.from(incoming).map((file) => {
      let base = file.name;
      const dot = file.name.lastIndexOf(".");
      if (dot > 0) base = file.name.substring(0, dot);

      let artist = "Unknown Artist";
      let title = base;

      if (base.includes(" - ")) {
        const parts = base.split(" - ");
        artist = parts[0].trim();
        title = parts.slice(1).join(" - ").trim();
      }

      return {
        file,
        title,
        artist,
        link: "",
      };
    });

    setStagedFiles((prev) => [...prev, ...parsed]);
    setIndexingStatus("idle");
    setFailureMsg("");
  };

  const removeStagedFile = (idx) => {
    setStagedFiles((prev) => prev.filter((_, i) => i !== idx));
  };

  const updateStagedMetadata = (idx, key, val) => {
    setStagedFiles((prev) =>
      prev.map((item, i) => (i === idx ? { ...item, [key]: val } : item))
    );
  };

  const executeBatchIndex = async () => {
    if (stagedFiles.length === 0) return;

    setIndexingStatus("uploading");
    setUploadPercent(0);
    setFailureMsg("");

    const formData = new FormData();
    stagedFiles.forEach((item) => {
      formData.append("file", item.file);
      formData.append("title", item.title);
      formData.append("artist", item.artist);
      formData.append("link", item.link);
    });
    formData.append("command", "getFingerprint");

    try {
      const res = await axios.post(`${API_BASE}/api/file`, formData, {
        onUploadProgress: (e) => {
          if (e.total) {
            const pct = Math.round((e.loaded * 100) / e.total);
            setUploadPercent(pct);
            if (pct >= 100) {
              setIndexingStatus("processing");
            }
          }
        },
      });

      const data = res.data;
      if (data && data.songs) {
        setIndexedResults(data.songs);
        setIndexingStatus("success");
      } else {
        setIndexingStatus("success");
      }
    } catch (err) {
      console.error("Batch indexing failure:", err);
      setIndexingStatus("error");
      setFailureMsg(
        err.response?.data?.error ||
          "Failed to process and index audio. Verify C++ binary execution and database write permissions."
      );
    }
  };

  // YouTube Ingestion Handler
  const executeYouTubeIndex = async () => {
    const rawLines = youtubeUrlsText
      .split("\n")
      .map((l) => l.trim())
      .filter((l) => l.length > 0);

    if (rawLines.length === 0) {
      setFailureMsg("Please provide at least one valid YouTube URL or playlist link.");
      setIndexingStatus("error");
      return;
    }

    setIndexingStatus("processing");
    setFailureMsg("");

    try {
      const res = await axios.post(`${API_BASE}/api/youtube/index`, {
        urls: rawLines,
        maxTracks: parseInt(maxTracks, 10) || 5,
        quickSampleOnly: quickSampleOnly,
      });

      const data = res.data;
      if (data && data.songs && data.songs.length > 0) {
        setIndexedResults(data.songs);
        setIndexingStatus("success");
      } else {
        setFailureMsg("yt-dlp could not extract audio from the provided link(s). Please verify URLs.");
        setIndexingStatus("error");
      }
    } catch (err) {
      console.error("YouTube ingestion failed:", err);
      setIndexingStatus("error");
      setFailureMsg(
        err.response?.data?.error ||
          "YouTube ingestion failed. Verify yt-dlp is reachable and internet connectivity is active."
      );
    }
  };

  const resetStaging = () => {
    setStagedFiles([]);
    setYoutubeUrlsText("");
    setIndexingStatus("idle");
    setUploadPercent(0);
    setIndexedResults([]);
    setFailureMsg("");
  };

  const getLogTagClass = (level) => {
    switch (level) {
      case "ERROR":
      case "FATAL":
        return "log-tag-error";
      case "WARN":
        return "log-tag-warn";
      case "SUCCESS":
      case "COMPLETE":
        return "log-tag-success";
      case "FFTW3":
      case "DSP":
        return "log-tag-dsp";
      case "YT-DLP":
      case "RESOLVING":
        return "log-tag-yt";
      case "MONGO":
        return "log-tag-mongo";
      default:
        return "log-tag-info";
    }
  };

  return (
    <div className="indexer-station animate-fade-in">
      <div className="indexer-header">
        <h1>Acoustic Ingestion Console</h1>
        <p className="indexer-lead">
          Catalog audio tracks into the fingerprint database via local file uploads or direct streaming downloads.
        </p>
      </div>

      {/* Source Selector */}
      <div className="source-toggle-group">
        <button
          type="button"
          className={`source-toggle-btn ${sourceMode === "files" ? "active" : ""}`}
          onClick={() => {
            setSourceMode("files");
            setIndexingStatus("idle");
            setFailureMsg("");
          }}
          disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="12" y1="18" x2="12" y2="12"/>
            <line x1="9" y1="15" x2="15" y2="15"/>
          </svg>
          Local Audio Files (WAV / MP3)
        </button>

        <button
          type="button"
          className={`source-toggle-btn ${sourceMode === "youtube" ? "active" : ""}`}
          onClick={() => {
            setSourceMode("youtube");
            setIndexingStatus("idle");
            setFailureMsg("");
          }}
          disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
        >
          <svg viewBox="0 0 24 24" width="16" height="16" fill="#ff0000">
            <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
          </svg>
          YouTube & Web Importer (yt-dlp)
        </button>
      </div>

      {/* SUCCESS STATE */}
      {indexingStatus === "success" && (
        <div className="studio-card indexer-success-pod animate-fade-in">
          <div className="success-badge-top mono">STATUS: 200_INDEXING_SUCCESS</div>
          <h2>{indexedResults.length} Tracks Cataloged Successfully</h2>
          <p className="success-meta">
            Acoustic constellations have been extracted via C++ FFTW3 and stored in MongoDB.
          </p>

          <div className="indexed-master-list">
            {indexedResults.map((s) => (
              <div key={s.id} className="indexed-master-row">
                <div className="master-title-cell">
                  <span className="master-id mono">#{s.id}</span>
                  <div>
                    <span className="master-name">{s.name}</span>
                    <span className="master-artist">{s.artist}</span>
                  </div>
                </div>
                <span className="master-hashes mono">
                  {s.hashCount?.toLocaleString()} hashes stored
                </span>
              </div>
            ))}
          </div>

          <div className="success-action-row">
            <Link to="/library" className="btn btn-primary">
              View in Master Library
            </Link>
            <button type="button" className="btn btn-secondary" onClick={resetStaging}>
              Index More Tracks
            </button>
          </div>
        </div>
      )}

      {/* STAGING PANEL */}
      {indexingStatus !== "success" && (
        <div className="studio-card indexer-staging-card">
          {/* Error Message */}
          {indexingStatus === "error" && (
            <div className="indexer-error-box mono">
              <span>[ERROR] {failureMsg}</span>
            </div>
          )}

          {/* =======================
              SOURCE 1: LOCAL FILES
             ======================= */}
          {sourceMode === "files" && (
            <>
              <div
                className="staging-dropzone"
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  handleFilesAdded(e.dataTransfer.files);
                }}
              >
                <label htmlFor="batch-audio-input" className="staging-dropzone-label">
                  <div className="staging-dropzone-icon">
                    <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                      <polyline points="17 8 12 3 7 8"/>
                      <line x1="12" y1="3" x2="12" y2="15"/>
                    </svg>
                  </div>
                  <span className="staging-title">Select Master Audio Files to Index</span>
                  <span className="staging-subtitle mono">
                    WAV, MP3, FLAC, OGG • Drag & drop multiple files to batch process
                  </span>
                </label>
                <input
                  id="batch-audio-input"
                  type="file"
                  multiple
                  accept="audio/*"
                  className="file-hidden-input"
                  onChange={(e) => handleFilesAdded(e.target.files)}
                  disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
                />
              </div>

              {/* Staged Tracks Queue */}
              {stagedFiles.length > 0 && (
                <div className="staging-queue-section">
                  <div className="staging-queue-header">
                    <span className="queue-count mono">STAGED AUDIO FILES ({stagedFiles.length})</span>
                    <button
                      type="button"
                      className="queue-clear-btn mono"
                      onClick={() => setStagedFiles([])}
                      disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
                    >
                      CLEAR QUEUE
                    </button>
                  </div>

                  <div className="staging-queue-list">
                    {stagedFiles.map((item, idx) => (
                      <div key={idx} className="staging-track-row">
                        <div className="track-row-header">
                          <span className="track-source-name">📁 {item.file.name}</span>
                          <span className="track-source-size mono">{formatBytes(item.file.size)}</span>
                          {indexingStatus === "idle" && (
                            <button
                              type="button"
                              className="track-eject-btn"
                              onClick={() => removeStagedFile(idx)}
                              title="Remove file"
                            >
                              ✕
                            </button>
                          )}
                        </div>

                        <div className="track-row-inputs">
                          <div className="meta-field">
                            <label className="mono">TRACK TITLE</label>
                            <input
                              type="text"
                              value={item.title}
                              onChange={(e) => updateStagedMetadata(idx, "title", e.target.value)}
                              placeholder="Song Title"
                              disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
                            />
                          </div>

                          <div className="meta-field">
                            <label className="mono">ARTIST / ENSEMBLE</label>
                            <input
                              type="text"
                              value={item.artist}
                              onChange={(e) => updateStagedMetadata(idx, "artist", e.target.value)}
                              placeholder="Artist Name"
                              disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
                            />
                          </div>

                          <div className="meta-field">
                            <label className="mono">REFERENCE URL (OPTIONAL)</label>
                            <input
                              type="text"
                              value={item.link}
                              onChange={(e) => updateStagedMetadata(idx, "link", e.target.value)}
                              placeholder="https://..."
                              disabled={indexingStatus === "uploading" || indexingStatus === "processing"}
                            />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>

                  {indexingStatus === "uploading" && (
                    <div className="indexing-progress-box animate-fade-in">
                      <div className="progress-meter-track">
                        <div className="progress-meter-fill" style={{ width: `${uploadPercent}%` }}></div>
                      </div>
                      <span className="progress-label mono">
                        UPLOADING AUDIO BUFFER • {uploadPercent}%
                      </span>
                    </div>
                  )}

                  {indexingStatus === "idle" && (
                    <div className="staging-actions-bar">
                      <button
                        type="button"
                        className="btn btn-primary start-indexing-btn"
                        onClick={executeBatchIndex}
                      >
                        Execute Constellation Indexing ({stagedFiles.length} {stagedFiles.length === 1 ? "track" : "tracks"})
                      </button>
                    </div>
                  )}
                </div>
              )}
            </>
          )}

          {/* =======================
              SOURCE 2: YOUTUBE IMPORTER
             ======================= */}
          {sourceMode === "youtube" && (
            <div className="youtube-importer-deck">
              <div className="importer-instructions">
                <h3>Stream & Fingerprint from YouTube</h3>
                <p>
                  Paste YouTube video URLs, playlist links, or direct search queries. 
                  Optimized with <strong>audio-only extraction</strong>, <strong>4-thread chunk acceleration</strong>, and direct 16kHz mono WAV conversion.
                </p>
              </div>

              <div className="youtube-form-group">
                <label className="mono">YOUTUBE URLS OR PLAYLISTS (ONE PER LINE)</label>
                <textarea
                  className="youtube-textarea mono"
                  rows={4}
                  placeholder={`https://www.youtube.com/watch?v=...\nhttps://www.youtube.com/playlist?list=...\nytsearch3:chopin nocturne`}
                  value={youtubeUrlsText}
                  onChange={(e) => setYoutubeUrlsText(e.target.value)}
                  disabled={indexingStatus === "processing"}
                ></textarea>
              </div>

              <div className="importer-controls-row">
                <div className="limit-control">
                  <label className="mono">MAX TRACKS:</label>
                  <input
                    type="number"
                    min="1"
                    max="25"
                    value={maxTracks}
                    onChange={(e) => setMaxTracks(e.target.value)}
                    className="max-tracks-input mono"
                    disabled={indexingStatus === "processing"}
                  />
                </div>

                <div className="quick-sample-toggle">
                  <label className="mono toggle-label">
                    <input
                      type="checkbox"
                      checked={quickSampleOnly}
                      onChange={(e) => setQuickSampleOnly(e.target.checked)}
                      disabled={indexingStatus === "processing"}
                    />
                    <span>Turbo Mode (First 90s sample • 70% faster)</span>
                  </label>
                </div>

                <div className="preset-shortcuts">
                  <span className="preset-label mono">PRESETS:</span>
                  <button
                    type="button"
                    className="btn-preset mono"
                    onClick={() => setYoutubeUrlsText("https://www.youtube.com/watch?v=Rb0UmrCXxVA")}
                    disabled={indexingStatus === "processing"}
                  >
                    Mozart Best
                  </button>
                  <button
                    type="button"
                    className="btn-preset mono"
                    onClick={() => setYoutubeUrlsText("ytsearch2:chopin nocturne")}
                    disabled={indexingStatus === "processing"}
                  >
                    Chopin (2)
                  </button>
                </div>
              </div>

              {indexingStatus === "idle" && (
                <button
                  type="button"
                  className="btn btn-primary start-indexing-btn"
                  onClick={executeYouTubeIndex}
                >
                  ⚡ Download, Fingerprint & Index from YouTube
                </button>
              )}
            </div>
          )}

          {/* =========================================================
              LIVE SSE TELEMETRY & INDEXING TERMINAL
             ========================================================= */}
          <div className="indexing-terminal-container">
            <div className="terminal-header">
              <div className="terminal-dots">
                <span></span><span></span><span></span>
              </div>
              <span className="terminal-title mono">INDEXING TELEMETRY STREAM</span>
              <div className="terminal-actions">
                <span className="stream-live-indicator mono">
                  <span className="pulse-dot"></span> STREAM LIVE
                </span>
                <button
                  type="button"
                  className="terminal-toggle-btn mono"
                  onClick={() => setShowTerminal(!showTerminal)}
                >
                  {showTerminal ? "COLLAPSE" : "EXPAND"}
                </button>
              </div>
            </div>

            {showTerminal && (
              <div className="terminal-body mono">
                {logs.map((item, i) => (
                  <div key={i} className="terminal-line">
                    <span className="log-time">{item.time}</span>
                    <span className={`log-tag ${getLogTagClass(item.level)}`}>
                      [{item.level}]
                    </span>
                    <span className="log-msg">{item.message}</span>
                  </div>
                ))}
                <div ref={terminalEndRef} />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
