import { ChangeEvent, useState } from "react";
import axios from "axios";
// Import the CSS file you just created
import "./FileUploader.css";

type UploadStatus = "Idle" | "uploading" | "success" | "error";

export default function FileUploader() {
  const [files, setFiles] = useState<File[] | null>(null); // Renamed to 'files' for clarity
  const [status, setStatus] = useState<UploadStatus>("Idle");
  const [progress, setProgress] = useState(0);

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      const selectedFiles = Array.from(e.target.files);
      setFiles(selectedFiles);
      // Reset status when a new file is chosen
      setStatus("Idle");
      setProgress(0);
    }
  }

  async function handleUploading() {
    if (!files || files.length === 0) {
      console.log("No files selected!");
      return;
    }

    setStatus("uploading");
    setProgress(0);

    const formData = new FormData();

    // Loop through ALL selected files and append them
    files.forEach((f) => {
      formData.append("file", f);
    });

    // Append the command
    formData.append("command", "getFingerprint");

    try {
      console.log(`Sending ${files.length} files to backend...`);

      const response = await axios.post(
        "http://localhost:8080/api/file",
        formData,
        {
          onUploadProgress: (progressEvent) => {
            const prog = progressEvent.total
              ? Math.round((progressEvent.loaded * 100) / progressEvent.total)
              : 0;
            setProgress(prog);
          },
        },
      );

      console.log("Backend Response:", response.data);
      setStatus("success");
      setProgress(100);
    } catch (err) {
      setStatus("error");
      setProgress(0);
      console.error("Upload failed!", err);
    }
  }
  return (
    <div className="uploader-container">
      <h3>Add to Database</h3>

      {/* The Stylish Dropzone */}
      <label htmlFor="file-upload" className="dropzone">
        <span className="dropzone-icon">📁</span>
        <span className="dropzone-text">Click to select audio files</span>
      </label>

      {/* The Hidden Input */}
      <input
        id="file-upload"
        type="file"
        multiple
        onChange={handleFileChange}
        className="hidden-input"
        accept="audio/*"
      />

      {files && files.length > 0 && (
        <div className="file-details">
          <ul>
            {files.map((file, index) => (
              <li key={index}>
                <span className="file-name">🎵 {file.name}</span>
                <span className="file-size">
                  {(file.size / 1024 / 1024).toFixed(2)} MB
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {status !== "Idle" && status !== "error" && (
        <div className="progress-container">
          <div
            className="progress-bar"
            style={{ width: `${progress}%`, backgroundColor: "var(--primary)" }}
          ></div>
        </div>
      )}

      {files &&
        files.length > 0 &&
        status !== "uploading" &&
        status !== "success" && (
          <button className="upload-button" onClick={handleUploading}>
            Index {files.length > 1 ? `${files.length} Files` : "File"}
          </button>
        )}

      {status === "uploading" && (
        <p className="status-message uploading">
          Uploading and indexing... {progress}%
        </p>
      )}
      {status === "success" && (
        <p className="status-message success">
          All files added to database! 🎉
        </p>
      )}
      {status === "error" && (
        <p className="status-message error">
          Upload failed. Check your server connection.
        </p>
      )}
    </div>
  );
}
