import { useState, useEffect } from "react";
import { Link, useLocation } from "react-router-dom";
import axios from "axios";
import { API_BASE } from "../config/api";
import "./Navbar.css";

export default function Navbar() {
  const location = useLocation();
  const [engineStatus, setEngineStatus] = useState("checking");
  const [stats, setStats] = useState({ totalSongs: 0, totalHashes: 0 });

  const fetchStatus = async () => {
    try {
      const res = await axios.get(`${API_BASE}/api/stats`, { timeout: 3000 });
      if (res.data && res.data.status === "ONLINE") {
        setEngineStatus("online");
        setStats({
          totalSongs: res.data.totalSongs || 0,
          totalHashes: res.data.totalHashes || 0,
        });
      } else {
        setEngineStatus("offline");
      }
    } catch {
      setEngineStatus("offline");
    }
  };

  useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 8000);
    return () => clearInterval(interval);
  }, []);

  return (
    <header className="studio-navbar">
      <div className="navbar-content">
        {/* Brand */}
        <Link to="/recognize" className="studio-brand">
          <div className="brand-logo-mark">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M12 2v20M17 5v14M7 8v8M22 10v4M2 11v2" />
            </svg>
          </div>
          <div className="brand-copy">
            <span className="brand-name">SHAZAM <span className="brand-edition">DSP</span></span>
            <span className="brand-tagline mono">Acoustic Constellation Engine</span>
          </div>
        </Link>

        {/* Navigation Tabs */}
        <nav className="studio-nav">
          <Link
            to="/recognize"
            className={`nav-tab ${location.pathname === "/recognize" || location.pathname === "/" ? "active" : ""}`}
          >
            <span className="tab-indicator"></span>
            <span>Identify Audio</span>
          </Link>

          <Link
            to="/library"
            className={`nav-tab ${location.pathname === "/library" ? "active" : ""}`}
          >
            <span className="tab-indicator"></span>
            <span>Acoustic Library</span>
            {stats.totalSongs > 0 && (
              <span className="tab-counter mono">{stats.totalSongs}</span>
            )}
          </Link>

          <Link
            to="/add"
            className={`nav-tab ${location.pathname === "/add" ? "active" : ""}`}
          >
            <span className="tab-indicator"></span>
            <span>Index Master</span>
          </Link>
        </nav>

        {/* Hardware Telemetry Bar */}
        <div className="navbar-telemetry">
          <div className={`telemetry-tag ${engineStatus === "online" ? "active" : ""}`}>
            <span className="status-beacon"></span>
            <span className="mono">
              {engineStatus === "online" 
                ? `FFTW3 • ${stats.totalHashes.toLocaleString()} HASHES`
                : "DAEMON OFFLINE"}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}
