import { BrowserRouter as Router, Routes, Route } from "react-router-dom";
import Navbar from "./Component/Navbar";
import FileUploader from "./Component/FileUploader";
import SongRecognizer from "./Component/SongRecognizer";
import "./App.css"; // Assuming you have standard App CSS

function App() {
  return (
    <Router>
      <div className="app-container">
        {/* The Navbar stays at the top of every page */}
        <Navbar />

        {/* The Routes decide which component to show below the Navbar */}
        <div
          className="page-content"
          style={{ padding: "0 20px", maxWidth: "800px", margin: "0 auto" }}
        >
          <Routes>
            {/* The default page (http://localhost:5173/) */}
            <Route path="/" element={<FileUploader />} />

            {/* The recognize page (http://localhost:5173/recognize) */}
            <Route path="/recognize" element={<SongRecognizer />} />
          </Routes>
        </div>
      </div>
    </Router>
  );
}

export default App;
