import { ChangeEvent, useState } from "react";
import axios from "axios";
import "./FileUploader.css"; // We can reuse your existing CSS!

type RecognizeStatus = "Idle" | "recognizing" | "success" | "error";

export default function SongRecognizer() {
  const [file, setFile] = useState<File | null>(null); // Only ONE file this time
  const [status, setStatus] = useState<RecognizeStatus>("Idle");
  const [result, setResult] = useState<string>(""); // Stores the matched song name

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    if (e.target.files && e.target.files.length > 0) {
      setFile(e.target.files[0]); // Grab only the first file
      setStatus("Idle");
      setResult("");
    }
  }

  async function handleRecognize() {
    if (!file) {
      console.log("No file selected!");
      return;
    }

    setStatus("recognizing");
    setResult("");

    const formData = new FormData();
    formData.append("file", file);

    // Assuming your C++ program uses the same command to generate the hash for matching
    formData.append("command", "getFingerprint");

    try {
      console.log("Sending sample to backend for recognition...");

      // Hit the /recognize endpoint!
      const response = await axios.post(
        "http://localhost:8080/api/recognize",
        formData,
      );

      console.log("Recognition Response:", response.data);

      // The backend returns a String with the result, so we save it to state
      setResult(response.data);
      setStatus("success");
    } catch (err) {
      setStatus("error");
      console.error("Recognition failed!", err);
      setResult("An error occurred while trying to recognize the song.");
    }
  }

  return (
    <div className="uploader-container">
      <h3>Recognize Song</h3>

      <label
        htmlFor="sample-upload"
        className={`dropzone recognizer ${status === "recognizing" ? "pulse-animation" : ""}`}
      >
        <span className="dropzone-icon">🎙️</span>
        <span className="dropzone-text">
          {status === "recognizing"
            ? "Listening to audio..."
            : "Click to select audio sample"}
        </span>
      </label>

      <input
        id="sample-upload"
        type="file"
        onChange={handleFileChange}
        className="hidden-input"
        accept="audio/*"
        disabled={status === "recognizing"}
      />

      {file && status !== "success" && (
        <div className="file-details">
          <ul>
            <li>
              <span className="file-name"> {file.name}</span>
              <span className="file-size">
                {(file.size / 1024 / 1024).toFixed(2)} MB
              </span>
            </li>
          </ul>
        </div>
      )}

      {file && status !== "recognizing" && status !== "success" && (
        <button
          className="upload-button recognize-btn"
          onClick={handleRecognize}
        >
          Identify Song
        </button>
      )}

      {status === "success" && (
        <div
          className="file-details"
          style={{
            backgroundColor: "rgba(0, 136, 255, 0.1)",
            border: "1px solid var(--shazam-blue)",
          }}
        >
          <h4 style={{ margin: "0 0 10px 0", color: "var(--shazam-blue)" }}>
            Match Found!
          </h4>
          <p style={{ margin: 0, fontSize: "1.2rem", fontWeight: "bold" }}>
            {result}
          </p>
        </div>
      )}

      {status === "error" && <p className="status-message error">{result}</p>}
    </div>
  );
}
