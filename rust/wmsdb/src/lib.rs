//! Embedded SurrealDB (SurrealKV backend) for the movFast PDA — JNI spike.
//!
//! One engine instance per process (SurrealKV is single-writer; the `:trips`
//! process must NOT open the same path — see ScannerManager-style process
//! gating on the Kotlin side). Hand-rolled JNI keeps the spike free of
//! UniFFI codegen tooling; the real integration can switch later.
//!
//! Kotlin surface (object WmsDb):
//!   external fun open(path: String): Boolean
//!   external fun query(sql: String): String   // JSON array of result sets
//!   external fun close()

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use surrealdb::engine::any::Any;
use surrealdb::Surreal;
use tokio::runtime::Runtime;

static RT: OnceCell<Runtime> = OnceCell::new();
static DB: OnceCell<Surreal<Any>> = OnceCell::new();

fn runtime() -> &'static Runtime {
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            // The PDA is a phone, not a server: two workers are plenty and
            // keep the thread bill low next to CameraX/ML Kit/Compose.
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// Open (or create) the embedded DB at `path` (app filesDir subdir).
/// Idempotent: a second call on an already-open engine returns true.
#[no_mangle]
pub extern "system" fn Java_com_xelth_eckwms_1movfast_data_surreal_WmsDb_open(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    if DB.get().is_some() {
        return JNI_TRUE;
    }
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let endpoint = format!("surrealkv://{path}");
    let result = runtime().block_on(async {
        let db = surrealdb::engine::any::connect(endpoint).await?;
        db.use_ns("eck").use_db("wms").await?;
        Ok::<_, surrealdb::Error>(db)
    });
    match result {
        Ok(db) => {
            let _ = DB.set(db);
            JNI_TRUE
        }
        Err(e) => {
            log_err(&mut env, &format!("open failed: {e}"));
            JNI_FALSE
        }
    }
}

/// Run a SurrealQL query; returns a JSON array (one element per statement)
/// or a `{"error": "..."}` object.
#[no_mangle]
pub extern "system" fn Java_com_xelth_eckwms_1movfast_data_surreal_WmsDb_query(
    mut env: JNIEnv,
    _class: JClass,
    sql: JString,
) -> jstring {
    let sql: String = match env.get_string(&sql) {
        Ok(s) => s.into(),
        Err(_) => return make_jstring(&mut env, r#"{"error":"bad sql string"}"#),
    };
    let Some(db) = DB.get() else {
        return make_jstring(&mut env, r#"{"error":"db not open"}"#);
    };
    let out = runtime().block_on(async {
        let mut resp = db.query(&sql).await?;
        let n = resp.num_statements();
        let mut sets = Vec::with_capacity(n);
        for i in 0..n {
            let vals: Vec<serde_json::Value> = resp.take(i).unwrap_or_default();
            sets.push(serde_json::Value::Array(vals));
        }
        Ok::<_, surrealdb::Error>(serde_json::Value::Array(sets).to_string())
    });
    let json = out.unwrap_or_else(|e| {
        serde_json::json!({ "error": e.to_string() }).to_string()
    });
    make_jstring(&mut env, &json)
}

/// Present for API symmetry. The engine lives for the process lifetime —
/// SurrealKV flushes on write, and Android kills processes, not apps.
#[no_mangle]
pub extern "system" fn Java_com_xelth_eckwms_1movfast_data_surreal_WmsDb_close(
    _env: JNIEnv,
    _class: JClass,
) {
}

fn make_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn log_err(env: &mut JNIEnv, msg: &str) {
    // Surface engine errors in logcat via android.util.Log.e — the spike has
    // no android logger crate on purpose (fewer deps).
    if let (Ok(tag), Ok(m)) = (env.new_string("WmsDb"), env.new_string(msg)) {
        if let Ok(cls) = env.find_class("android/util/Log") {
            let _ = env.call_static_method(
                cls,
                "e",
                "(Ljava/lang/String;Ljava/lang/String;)I",
                &[(&tag).into(), (&m).into()],
            );
        }
    }
}
