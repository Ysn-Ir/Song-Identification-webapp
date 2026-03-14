import {ChangeEvent, useState} from "react";

export default function FileUploader() {
    const [file,setFile] = useState<File | null>(null);

    function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
        if(e.target.files){
            setFile(e.target.files[0]);
        }
    }
    return (
        <div>
            <label>File:</label>
            <input type="file" onChange={handleFileChange} />
            {
                file &&
                (<div>
                    <p>{file.name}</p>
                    <p>{file.size}</p>
                    <p>{file.type}</p>
                </div>)
            }
        </div>
    );
}