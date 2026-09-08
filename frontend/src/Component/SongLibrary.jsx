import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import axios from "axios";
import { API_BASE } from "../config/api";
import "./SongLibrary.css";

export default function SongLibrary() {
  const [songs, setSongs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [deletingId, setDeletingId] = useState(null);
  const [stats, setStats] = useState({ totalSongs: 0, totalHashes: 0 });

  const loadCatalog = async () => {
    setLoading(true);
    try {
      const [songsRes, statsRes] = await Promise.all([
        axios.get(`${API_BASE}/api/songs`),
        axios.get(`${API_BASE}/api/stats`),
      ]);
      setSongs(songsRes.data || []);
      setStats({
        totalSongs: statsRes.data?.totalSongs || (songsRes.data?.length || 0),
        totalHashes: statsRes.data?.totalHashes || 0,
      });
    } catch (err) {
      console.error("Failed to load catalog data:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCatalog();
  }, []);

  const handleDelete = async (id, name) => {
    if (!window.confirm(`Confirm removal of "${name}" (ID #${id}) and all its acoustic hash fingerprints from MongoDB?`)) {
      return;
    }
    setDeletingId(id);
    try {
      await axios.delete(`${API_BASE}/api/songs/${id}`);
      setSongs((prev) => prev.filter((s) => s.id !== id));
      const res = await axios.get(`${API_BASE}/api/stats`);
      if (res.data) {
        setStats({
          totalSongs: res.data.totalSongs,
          totalHashes: res.data.totalHashes,
        });
      }
    } catch (err) {
      console.error("Deletion error:", err);
      alert("Failed to delete track. Check server logs.");
    } finally {
      setDeletingId(null);
    }
  };

  const filtered = songs.filter((s) => {
    const q = query.toLowerCase();
    return (
      (s.name && s.name.toLowerCase().includes(q)) ||
      (s.artist && s.artist.toLowerCase().includes(q)) ||
      s.id.toString().includes(q)
    );
  });

  const avgHashes = stats.totalSongs > 0 ? Math.round(stats.totalHashes / stats.totalSongs) : 0;

  return (
    <div className="catalog-station animate-fade-in">
      {/* Studio Header */}
      <div className="catalog-header-bar">
        <div className="header-text-group">
          <h1>Acoustic Master Catalog</h1>
          <p className="catalog-lead">
            Audited repository of reference tracks and constellation peak fingerprints indexed in MongoDB.
          </p>
        </div>

        <Link to="/add" className="btn btn-primary add-master-btn">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.5">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          Index Audio Master
        </Link>
      </div>

      {/* Hardware Telemetry Cards */}
      <div className="catalog-metrics-grid">
        <div className="metric-pod studio-card">
          <span className="pod-label mono">TOTAL AUDIO MASTERS</span>
          <span className="pod-val mono">{stats.totalSongs}</span>
          <span className="pod-sub mono">Indexed in cluster</span>
        </div>

        <div className="metric-pod studio-card">
          <span className="pod-label mono">CONSTELLATION HASHES</span>
          <span className="pod-val highlight-cyan mono">{stats.totalHashes.toLocaleString()}</span>
          <span className="pod-sub mono">Acoustic pairs in DB</span>
        </div>

        <div className="metric-pod studio-card">
          <span className="pod-label mono">AVG DENSITY / TRACK</span>
          <span className="pod-val mono">{avgHashes.toLocaleString()}</span>
          <span className="pod-sub mono">Hashes / Master file</span>
        </div>

        <div className="metric-pod studio-card">
          <span className="pod-label mono">ENGINE DAEMON</span>
          <span className="pod-val status-online mono">ONLINE</span>
          <span className="pod-sub mono">Spring Boot 4 / FFTW3</span>
        </div>
      </div>

      {/* Search & Filter Toolbar */}
      <div className="catalog-toolbar">
        <div className="toolbar-search-box">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" className="search-glyph">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            type="text"
            placeholder="Filter catalog by title, artist, or track ID..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="toolbar-search-input mono"
          />
          {query && (
            <button
              type="button"
              className="toolbar-clear-btn mono"
              onClick={() => setQuery("")}
            >
              CLEAR
            </button>
          )}
        </div>
      </div>

      {/* Catalog Table / Grid */}
      {loading ? (
        <div className="studio-card catalog-loading-pod">
          <div className="hardware-spinner"></div>
          <span className="mono">Querying MongoDB cluster...</span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="studio-card catalog-empty-pod">
          <div className="empty-glyph">
            <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M9 18V5l12-2v13"/>
              <circle cx="6" cy="18" r="3"/>
              <circle cx="18" cy="16" r="3"/>
            </svg>
          </div>
          <h3>{query ? "No Matching Tracks" : "Catalog Contains No Masters"}</h3>
          <p>
            {query
              ? `No cataloged master matches your filter criteria "${query}".`
              : "Upload audio files to generate constellation hashes and populate the identification database."}
          </p>
          {!query && (
            <Link to="/add" className="btn btn-primary">
              Index Reference Tracks Now
            </Link>
          )}
        </div>
      ) : (
        <div className="catalog-table-wrapper studio-card">
          <table className="catalog-table">
            <thead>
              <tr className="mono">
                <th>ID</th>
                <th>TRACK MASTER</th>
                <th>ARTIST</th>
                <th>CONSTELLATION HASHES</th>
                <th>ACTIONS</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((song) => (
                <tr key={song.id} className="catalog-row">
                  <td className="row-id mono">#{song.id}</td>
                  <td className="row-track-name">
                    <div className="track-cell">
                      <div className="track-waveform-glyph">
                        <span></span><span></span><span></span><span></span><span></span>
                      </div>
                      <div className="track-title-block">
                        <span className="track-title-text">{song.name}</span>
                        {song.link && (
                          <a
                            href={song.link}
                            target="_blank"
                            rel="noreferrer"
                            className="track-source-url mono"
                            title={song.link}
                          >
                            <svg viewBox="0 0 24 24" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="2">
                              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
                              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
                            </svg>
                            {song.link.replace(/^https?:\/\/(www\.)?/, "").length > 32
                              ? song.link.replace(/^https?:\/\/(www\.)?/, "").substring(0, 32) + "..."
                              : song.link.replace(/^https?:\/\/(www\.)?/, "")}
                          </a>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="row-artist">{song.artist || "Unknown Artist"}</td>
                  <td className="row-hashes mono">
                    <span className="hash-metric-pill">
                      {song.hashCount ? song.hashCount.toLocaleString() : 0} hashes
                    </span>
                  </td>
                  <td className="row-actions">
                    <div className="action-button-group">
                      {song.link && (
                        <a
                          href={song.link}
                          target="_blank"
                          rel="noreferrer"
                          className="btn-icon-link source-direct"
                          title="Open direct audio source link"
                        >
                          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
                            <polyline points="15 3 21 3 21 9"/>
                            <line x1="10" y1="14" x2="21" y2="3"/>
                          </svg>
                        </a>
                      )}

                      <a
                        href={`https://open.spotify.com/search/${encodeURIComponent(
                          `${song.artist} ${song.name}`
                        )}`}
                        target="_blank"
                        rel="noreferrer"
                        className="btn-icon-link spotify"
                        title="Search on Spotify"
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
                          <path d="M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"/>
                        </svg>
                      </a>

                      <a
                        href={
                          song.link && song.link.includes("youtube")
                            ? song.link
                            : `https://www.youtube.com/results?search_query=${encodeURIComponent(
                                `${song.artist} ${song.name}`
                              )}`
                        }
                        target="_blank"
                        rel="noreferrer"
                        className="btn-icon-link youtube"
                        title={song.link && song.link.includes("youtube") ? "Watch on YouTube" : "Search on YouTube"}
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
                          <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
                        </svg>
                      </a>

                      <button
                        type="button"
                        className="btn-delete mono"
                        onClick={() => handleDelete(song.id, song.name)}
                        disabled={deletingId === song.id}
                        title="Delete track and fingerprints"
                      >
                        {deletingId === song.id ? "DELETING" : "DELETE"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
