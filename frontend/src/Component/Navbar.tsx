import { Link, useLocation } from "react-router-dom";
import "./Navbar.css"; // We'll create a tiny bit of CSS for this next

export default function Navbar() {
  const location = useLocation();

  return (
    <nav className="navbar">
      <div className="navbar-logo">
        <h2>🎶 Shazam Clone</h2>
      </div>
      <div className="navbar-links">
        <Link to="/" className={location.pathname === "/" ? "active" : ""}>
          Add to Database
        </Link>
        <Link
          to="/recognize"
          className={location.pathname === "/recognize" ? "active" : ""}
        >
          Recognize Song
        </Link>
      </div>
    </nav>
  );
}
