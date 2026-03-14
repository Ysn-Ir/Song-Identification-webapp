import { ChangeEvent, useState } from "react";
import axios from "axios";
// Import the CSS file you just created
import "./FileUploader.css";

type UploadStatus = "Idle" | "uploading" | "success" | "error";

export default function FileUploader() {
  const [file, setFile] = useState<File | null>(null);
  const [status, setStatus] = useState<UploadStatus>("Idle");
  const [progress, setProgress] = useState(0);

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    if (e.target.files) {
      setFile(e.target.files[0]);
      // Reset status when a new file is chosen
      setStatus("Idle");
      setProgress(0);
    }
  }

  async function handleUploading() {
    if (!file) return;

    setStatus("uploading");
    setProgress(0);

    const Formdata = new FormData();
    Formdata.append("file", file);

    try {
      await axios.post("http://localhost:8080/api/upload", Formdata, {
        headers: {
          "Content-Type": "multipart/form-data",
        },
        onUploadProgress: (progressEvent) => {
          const prog = progressEvent.total
            ? Math.round((progressEvent.loaded * 100) / progressEvent.total)
            : 0;
          setProgress(prog);
        },
      });
      setStatus("success");
      setProgress(100);
    } catch (err) {
      setStatus("error");
      setProgress(0);
      console.log("Error", err);
    }
  }

  return (
    <div className="uploader-container">
      <h3>Upload a File</h3>

      <input type="file" onChange={handleFileChange} className="file-input" />

      {file && (
        <div className="file-details">
          <p>
            <strong>Name:</strong> {file.name}
          </p>
          <p>
            <strong>Size:</strong> {(file.size / 1024 / 1024).toFixed(2)} MB
          </p>
          <p>
            <strong>Type:</strong> {file.type || "Unknown"}
          </p>
        </div>
      )}

      {/* Progress Bar (Only visible during and after upload) */}
      {status !== "Idle" && status !== "error" && (
        <div className="progress-container">
          <div className="progress-bar" style={{ width: `${progress}%` }}></div>
        </div>
      )}

      {/* Upload Button */}
      {file && status !== "uploading" && status !== "success" && (
        <button className="upload-button" onClick={handleUploading}>
          Upload File
        </button>
      )}

      {/* Status Messages */}
      {status === "uploading" && (
        <p className="status-message uploading">Uploading... {progress}%</p>
      )}
      {status === "success" && (
        <p className="status-message success">
          File has been uploaded successfully!
        </p>
      )}
      {status === "error" && (
        <p className="status-message error">Upload error. Please try again.</p>
      )}
    </div>
  );
}
