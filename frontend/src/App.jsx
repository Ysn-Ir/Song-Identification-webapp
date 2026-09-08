import { BrowserRouter as Router, Routes, Route, Navigate } from "react-router-dom";
import Navbar from "./Component/Navbar";
import SongRecognizer from "./Component/SongRecognizer";
import SongLibrary from "./Component/SongLibrary";
import FileUploader from "./Component/FileUploader";
import "./App.css";

function App() {
  return (
    <Router>
      <div className="app-container">
        {/* Sticky Glassmorphic Navbar with Live Engine Status */}
        <Navbar />

        {/* Dynamic Route Content */}
        <main className="page-content">
          <Routes>
            {/* Default page redirects to live recognition */}
            <Route path="/" element={<Navigate to="/recognize" replace />} />

            {/* Song Recognition (Microphone listening & File drop) */}
            <Route path="/recognize" element={<SongRecognizer />} />

            {/* Catalog Browser (Inspect, search, and delete indexed songs) */}
            <Route path="/library" element={<SongLibrary />} />

            {/* Batch Music Indexer (Add songs with tags into the database) */}
            <Route path="/add" element={<FileUploader />} />

            {/* Catch-all fallback */}
            <Route path="*" element={<Navigate to="/recognize" replace />} />
          </Routes>
        </main>
      </div>
    </Router>
  );
}

export default App;
