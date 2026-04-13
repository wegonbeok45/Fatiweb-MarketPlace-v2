import os
from google.cloud import firestore
import google.auth
from google.cloud.firestore_v1 import DELETE_FIELD

# Step 1: Update admin user role field
def update_admin_role():
    try:
        # Load from the local service account JSON
        db = firestore.Client.from_service_account_json("service_account.json")
        doc_ref = db.collection("users").document("4kJuqCSeY4hJAFImGynshn12d2T2")
        
        # Check current data
        doc = doc_ref.get()
        if not doc.exists:
            print("ERROR: Admin user document not found!")
            return

        print(f"Current data: {doc.to_dict()}")
        
        # Action: Remove 'Role' (if it exists) and add 'role' => 'admin'
        data_to_update = {
            "role": "admin"
        }
        
        # If 'Role' exists, delete it
        if "Role" in doc.to_dict():
            data_to_update["Role"] = DELETE_FIELD
            print("Deleting existing 'Role' field...")

        doc_ref.update(data_to_update)
        print("SUCCESS: Admin user role updated successfully!")
        
        # Verify
        new_doc = doc_ref.get()
        print(f"New data: {new_doc.to_dict()}")

    except Exception as e:
        print(f"EXCEPTION: {str(e)}")

if __name__ == "__main__":
    update_admin_role()
