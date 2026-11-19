//! Cadence and lifetime for the single still requested by a paired remote.
use serde_json::Value;
use std::time::{Duration, Instant};

const INTEREST_LIFETIME: Duration = Duration::from_secs(4);
const REFRESH_INTERVAL: Duration = Duration::from_secs(12);
const MIN_DECODE_INTERVAL: Duration = Duration::from_secs(2);
const SEEK_SETTLE: Duration = Duration::from_millis(750);

#[derive(PartialEq)]
struct Source {
    id: String,
    path: String,
}

pub(super) struct Job {
    pub generation: u64,
    pub path: String,
    pub position: f64,
}

pub(super) struct Frame {
    pub id: String,
    pub canonical_path: String,
    pub jpeg: String,
}

#[derive(Default)]
pub(super) struct Preview {
    source: Option<Source>,
    requested_at: Option<Instant>,
    last_observed: Option<(Instant, f64)>,
    last_started: Option<Instant>,
    last_position: Option<f64>,
    wait_until: Option<Instant>,
    generation: u64,
    in_flight: bool,
    frame: Option<Frame>,
    decode_failed: bool,
}

impl Preview {
    pub fn request(&mut self, want: bool, now: Instant) {
        self.requested_at = want.then_some(now);
    }

    fn interested(&self, now: Instant) -> bool {
        self.requested_at.is_some_and(|at| now.duration_since(at) <= INTEREST_LIFETIME)
    }

    pub fn forget(&mut self) {
        self.requested_at = None;
        self.invalidate();
    }

    fn invalidate(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        self.last_position = None;
        self.frame = None;
        self.decode_failed = false;
    }

    pub fn seek(&mut self, now: Instant) {
        self.invalidate();
        self.wait_until = Some(now + SEEK_SETTLE);
    }

    pub fn observe(&mut self, snapshot: &Value, path: Option<String>, now: Instant) -> Option<Job> {
        let position = snapshot.get("position").and_then(Value::as_f64);
        let source = path.zip(snapshot.get("sourceId").and_then(Value::as_str))
            .filter(|(path, id)| {
                !id.is_empty() && snapshot.get("ready").and_then(Value::as_bool) == Some(true)
                    && !crate::thumb_service::is_private_preview_path(path)
            })
            .map(|(path, id)| Source { id: id.to_owned(), path });
        if source != self.source {
            self.invalidate();
            self.source = source;
            self.last_observed = None;
            self.wait_until = None;
        }
        let (Some(position), Some(_)) = (position, self.source.as_ref()) else { return None };
        let jump = self.last_observed.is_some_and(|(at, previous)| {
            let elapsed = now.duration_since(at).as_secs_f64();
            position < previous - 1.5 || position > previous + 2.0 + 4.0 * elapsed
        });
        self.last_observed = Some((now, position));
        if jump {
            self.seek(now);
        }
        let since_start = self.last_started.map(|at| now.duration_since(at));
        if !self.interested(now)
            || self.in_flight
            || self.wait_until.is_some_and(|until| now < until)
            || since_start.is_some_and(|elapsed| elapsed < MIN_DECODE_INTERVAL)
        {
            return None;
        }
        if let Some(last) = self.last_position {
            if since_start.is_some_and(|elapsed| elapsed < REFRESH_INTERVAL)
                || (self.frame.is_some() && (position - last).abs() < 2.0)
            {
                return None;
            }
        }
        let source = self.source.as_ref()?;
        self.in_flight = true;
        self.last_started = Some(now);
        self.last_position = Some(position);
        Some(Job { generation: self.generation, path: source.path.clone(), position: position.max(0.0) })
    }

    pub fn complete(&mut self, generation: u64, frame: Option<Frame>, now: Instant) {
        self.in_flight = false;
        if generation != self.generation {
            return;
        }
        if !self.interested(now) {
            // A resumed remote should not have to wait for the refresh interval
            // for a result that was discarded while it was away.
            self.last_position = None;
            return;
        }
        match frame {
            Some(frame) => {
                self.frame = Some(frame);
                self.decode_failed = false;
            }
            None => self.decode_failed = true,
        }
    }

    pub(super) fn status(&self, snapshot_ready: bool, has_frame: bool) -> &'static str {
        if has_frame {
            "ready"
        } else if snapshot_ready && self.source.is_none() {
            "unsupported_source"
        } else if self.decode_failed {
            "decode_failed"
        } else {
            "waiting"
        }
    }

    pub fn frame(&self) -> Option<&Frame> {
        let source = self.source.as_ref()?;
        let frame = self.frame.as_ref()?;
        (!crate::thumb_service::is_private_preview_path(&source.path)
            && !crate::thumb_service::is_private_preview_path(&frame.canonical_path)).then_some(frame)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn snapshot(id: &str, position: f64) -> Value {
        json!({ "ready": true, "sourceId": id, "position": position })
    }

    #[test]
    fn preview_requires_recent_interest_and_waits_for_seek_to_settle() {
        let now = Instant::now();
        let mut preview = Preview::default();
        let path = || Some("local.mov".to_owned());
        assert!(preview.observe(&snapshot("a", 0.0), path(), now).is_none());
        preview.request(true, now);
        let first = preview.observe(&snapshot("a", 0.0), path(), now).unwrap();
        assert!(preview.observe(&snapshot("a", 0.1), path(), now).is_none());
        let seek_at = now + Duration::from_secs(2);
        preview.seek(seek_at);
        preview.complete(first.generation, None, seek_at);
        assert!(preview.observe(&snapshot("a", 55.0), path(), seek_at).is_none());
        let later = seek_at + SEEK_SETTLE;
        assert_eq!(preview.observe(&snapshot("a", 55.0), path(), later).unwrap().position, 55.0);
        assert!(!preview.interested(now + Duration::from_secs(5)));
    }

    #[test]
    fn obsolete_generation_never_restores_a_frame_after_source_change() {
        let now = Instant::now();
        let mut preview = Preview::default();
        preview.request(true, now);
        let job = preview.observe(&snapshot("first", 0.0), Some("first.mov".into()), now).unwrap();
        assert!(preview.observe(&snapshot("next", 0.0), Some("second.mov".into()), now).is_none());
        preview.complete(job.generation, Some(Frame { id: "old".into(), canonical_path: "first.mov".into(), jpeg: "old".into() }), now);
        assert!(preview.frame().is_none());
    }

    #[test]
    fn unchanged_position_does_not_refresh_a_still() {
        let now = Instant::now();
        let mut preview = Preview::default();
        preview.request(true, now);
        let path = || Some("local.mov".to_owned());
        let job = preview.observe(&snapshot("a", 9.0), path(), now).unwrap();
        preview.complete(job.generation, Some(Frame { id: "preview".into(), canonical_path: "local.mov".into(), jpeg: "frame".into() }), now);
        let later = now + REFRESH_INTERVAL;
        preview.request(false, later);
        assert!(preview.observe(&snapshot("a", 9.0), path(), later).is_none());
        assert_eq!(preview.frame().map(|frame| frame.id.as_str()), Some("preview"));
        preview.request(true, later);
        assert!(preview.observe(&snapshot("a", 9.0), path(), later).is_none());
    }

    #[test]
    fn status_distinguishes_waiting_from_unsupported_and_failed_decode() {
        let now = Instant::now();
        let mut preview = Preview::default();
        assert_eq!(preview.status(false, false), "waiting");
        preview.observe(&snapshot("unsupported", 0.0), None, now);
        assert_eq!(preview.status(true, false), "unsupported_source");
        preview.request(true, now);
        let job = preview.observe(&snapshot("local", 0.0), Some("local.mov".into()), now).unwrap();
        assert_eq!(preview.status(true, false), "waiting");
        preview.complete(job.generation, None, now);
        assert_eq!(preview.status(true, false), "decode_failed");
        preview.observe(&snapshot("other", 0.0), Some("other.mov".into()), now);
        assert_eq!(preview.status(true, false), "waiting");
    }
}
