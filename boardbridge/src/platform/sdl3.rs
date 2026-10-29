// Copyright 2026 The DiamondLauncher, Horizon, and BoardBridge Authors
// Licensed under the Apache License, Version 2.0.

//! SDL3 platform backend for Minecraft: Java Edition 26.3+ and Horizon.
//!
//! Minecraft 26.3 replaced GLFW with SDL3 as its platform layer. This module
//! translates BoardBridge's queued [`InputEvent`]s (which already carry real
//! `SDL_Scancode`, `SDL_Keycode`, `SDL_KMOD_*`, `SDL_GamepadAxis`, and
//! `SDL_GamepadButton` values from [`crate::input::sdl_tables`]) into
//! `SDL_Event` wire structures (`Sdl3EventPacket`), normalizes touch coordinates
//! to `0.0..=1.0` with per-finger `(dx, dy)` delta tracking, maps surface
//! lifecycle notices to `SDL_EVENT_WINDOW_*`, and exposes the SDL3 Android
//! window property names (`SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER` and
//! `SDL_PROP_WINDOW_ANDROID_SURFACE_POINTER`).

use std::collections::HashMap;
use std::sync::Mutex;

use crate::error::Result;
use crate::graphics::BackendStatus;
use crate::input::event::{InputEvent, LifecycleNotice, TouchPhase};
use crate::input::QueueStats;
use crate::platform::{PlatformBackend, PlatformKind, WindowInfo};
use crate::runtime::control::Shared;

/// SDL3 property for passing our owned `ANativeWindow*` to `SDL_CreateWindowWithProperties`.
pub const SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER: &str = "SDL.window.android.window";
/// SDL3 property for passing our bound `EGLSurface` to `SDL_CreateWindowWithProperties`.
pub const SDL_PROP_WINDOW_ANDROID_SURFACE_POINTER: &str = "SDL.window.android.surface";

// SDL3 `SDL_EventType` constants (from `SDL3/SDL_events.h`).
pub const SDL_EVENT_QUIT: u32 = 0x100;
pub const SDL_EVENT_WINDOW_SHOWN: u32 = 0x202;
pub const SDL_EVENT_WINDOW_HIDDEN: u32 = 0x203;
pub const SDL_EVENT_WINDOW_RESIZED: u32 = 0x206;
pub const SDL_EVENT_WINDOW_MINIMIZED: u32 = 0x209;
pub const SDL_EVENT_WINDOW_RESTORED: u32 = 0x20B;
pub const SDL_EVENT_WINDOW_DESTROYED: u32 = 0x213;
pub const SDL_EVENT_KEY_DOWN: u32 = 0x300;
pub const SDL_EVENT_KEY_UP: u32 = 0x301;
pub const SDL_EVENT_TEXT_INPUT: u32 = 0x303;
pub const SDL_EVENT_MOUSE_MOTION: u32 = 0x400;
pub const SDL_EVENT_MOUSE_BUTTON_DOWN: u32 = 0x401;
pub const SDL_EVENT_MOUSE_BUTTON_UP: u32 = 0x402;
pub const SDL_EVENT_MOUSE_WHEEL: u32 = 0x403;
pub const SDL_EVENT_GAMEPAD_AXIS_MOTION: u32 = 0x650;
pub const SDL_EVENT_GAMEPAD_BUTTON_DOWN: u32 = 0x651;
pub const SDL_EVENT_GAMEPAD_BUTTON_UP: u32 = 0x652;
pub const SDL_EVENT_FINGER_DOWN: u32 = 0x700;
pub const SDL_EVENT_FINGER_UP: u32 = 0x701;
pub const SDL_EVENT_FINGER_MOTION: u32 = 0x702;
pub const SDL_EVENT_FINGER_CANCELED: u32 = 0x703;

/// Translated SDL3 event packet ready for `SDL_PushEvent` or JNI consumption.
#[derive(Clone, Debug, PartialEq)]
pub struct Sdl3EventPacket {
    /// `SDL_EVENT_*` constant.
    pub event_type: u32,
    /// Event timestamp in nanoseconds (SDL3 uses ns; converted from `timestamp_ms * 1_000_000`).
    pub timestamp_ns: u64,
    /// Scancode, button, or axis id.
    pub code: i32,
    /// Keycode or secondary id.
    pub keycode: u32,
    /// `SDL_KMOD_*` bitmask or button state mask.
    pub modifiers: u16,
    /// Normalized or pixel X coordinate.
    pub x: f32,
    /// Normalized or pixel Y coordinate.
    pub y: f32,
    /// Relative X delta (`xrel` / `dx`).
    pub dx: f32,
    /// Relative Y delta (`yrel` / `dy`).
    pub dy: f32,
}

/// Tracks per-pointer normalized touch positions and last mouse coordinates for SDL3 delta calculation.
#[derive(Debug, Default)]
pub struct Sdl3StateTracker {
    fingers: HashMap<i32, (f32, f32)>,
    last_mouse: Option<(f32, f32)>,
}

impl Sdl3StateTracker {
    /// Creates a new SDL3 state tracker.
    pub fn new() -> Self {
        Self::default()
    }

    /// Translates a BoardBridge [`InputEvent`] into an [`Sdl3EventPacket`] given the active surface dimensions.
    pub fn translate(
        &mut self,
        event: &InputEvent,
        surface_width: i32,
        surface_height: i32,
    ) -> Sdl3EventPacket {
        let timestamp_ns = (event.timestamp_ms().max(0) as u64).saturating_mul(1_000_000);
        let w = (surface_width.max(1)) as f32;
        let h = (surface_height.max(1)) as f32;

        match event {
            InputEvent::Key {
                pressed,
                scancode,
                keycode,
                modifiers,
                ..
            } => Sdl3EventPacket {
                event_type: if *pressed {
                    SDL_EVENT_KEY_DOWN
                } else {
                    SDL_EVENT_KEY_UP
                },
                timestamp_ns,
                code: *scancode as i32,
                keycode: *keycode,
                modifiers: *modifiers,
                x: 0.0,
                y: 0.0,
                dx: 0.0,
                dy: 0.0,
            },
            InputEvent::Text { text, .. } => Sdl3EventPacket {
                event_type: SDL_EVENT_TEXT_INPUT,
                timestamp_ns,
                code: text.chars().next().map(|c| c as i32).unwrap_or(0),
                keycode: text.len() as u32,
                modifiers: 0,
                x: 0.0,
                y: 0.0,
                dx: 0.0,
                dy: 0.0,
            },
            InputEvent::MouseMotion {
                x,
                y,
                relative,
                buttons,
                ..
            } => {
                let (abs_x, abs_y, dx, dy) = if *relative {
                    let (prev_x, prev_y) = self.last_mouse.unwrap_or((w * 0.5, h * 0.5));
                    let nx = (prev_x + *x).clamp(0.0, w);
                    let ny = (prev_y + *y).clamp(0.0, h);
                    self.last_mouse = Some((nx, ny));
                    (nx, ny, *x, *y)
                } else {
                    let (dx, dy) = match self.last_mouse {
                        Some((px, py)) => (*x - px, *y - py),
                        None => (0.0, 0.0),
                    };
                    self.last_mouse = Some((*x, *y));
                    (*x, *y, dx, dy)
                };
                Sdl3EventPacket {
                    event_type: SDL_EVENT_MOUSE_MOTION,
                    timestamp_ns,
                    code: *buttons as i32,
                    keycode: u32::from(*relative),
                    modifiers: *buttons as u16,
                    x: abs_x,
                    y: abs_y,
                    dx,
                    dy,
                }
            }
            InputEvent::MouseButton {
                button,
                pressed,
                x,
                y,
                ..
            } => Sdl3EventPacket {
                event_type: if *pressed {
                    SDL_EVENT_MOUSE_BUTTON_DOWN
                } else {
                    SDL_EVENT_MOUSE_BUTTON_UP
                },
                timestamp_ns,
                code: button.sdl() as i32,
                keycode: u32::from(*pressed),
                modifiers: 0,
                x: *x,
                y: *y,
                dx: 0.0,
                dy: 0.0,
            },
            InputEvent::MouseWheel { x, y, .. } => Sdl3EventPacket {
                event_type: SDL_EVENT_MOUSE_WHEEL,
                timestamp_ns,
                code: 0,
                keycode: 0,
                modifiers: 0,
                x: *x,
                y: *y,
                dx: *x,
                dy: *y,
            },
            InputEvent::Touch {
                pointer_id,
                phase,
                x,
                y,
                pressure,
                ..
            } => {
                let norm_x = (*x / w).clamp(0.0, 1.0);
                let norm_y = (*y / h).clamp(0.0, 1.0);
                let (dx, dy) = match self.fingers.get(pointer_id) {
                    Some(&(px, py)) => (norm_x - px, norm_y - py),
                    None => (0.0, 0.0),
                };
                match phase {
                    TouchPhase::Down | TouchPhase::Move => {
                        self.fingers.insert(*pointer_id, (norm_x, norm_y));
                    }
                    TouchPhase::Up | TouchPhase::Cancel => {
                        self.fingers.remove(pointer_id);
                    }
                }
                let event_type = match phase {
                    TouchPhase::Down => SDL_EVENT_FINGER_DOWN,
                    TouchPhase::Move => SDL_EVENT_FINGER_MOTION,
                    TouchPhase::Up => SDL_EVENT_FINGER_UP,
                    TouchPhase::Cancel => SDL_EVENT_FINGER_CANCELED,
                };
                Sdl3EventPacket {
                    event_type,
                    timestamp_ns,
                    code: *pointer_id,
                    keycode: (*pressure * 1000.0) as u32,
                    modifiers: 0,
                    x: norm_x,
                    y: norm_y,
                    dx,
                    dy,
                }
            }
            InputEvent::GamepadAxis {
                device_id,
                axis,
                value,
                ..
            } => Sdl3EventPacket {
                event_type: SDL_EVENT_GAMEPAD_AXIS_MOTION,
                timestamp_ns,
                code: *axis,
                keycode: *device_id as u32,
                modifiers: 0,
                x: *value,
                y: 0.0,
                dx: 0.0,
                dy: 0.0,
            },
            InputEvent::GamepadButton {
                device_id,
                button,
                pressed,
                ..
            } => Sdl3EventPacket {
                event_type: if *pressed {
                    SDL_EVENT_GAMEPAD_BUTTON_DOWN
                } else {
                    SDL_EVENT_GAMEPAD_BUTTON_UP
                },
                timestamp_ns,
                code: *button,
                keycode: *device_id as u32,
                modifiers: u16::from(*pressed),
                x: 0.0,
                y: 0.0,
                dx: 0.0,
                dy: 0.0,
            },
            InputEvent::Lifecycle(notice) => {
                let (event_type, nx, ny) = match notice {
                    LifecycleNotice::SurfaceAvailable { width, height, .. } => {
                        (SDL_EVENT_WINDOW_RESIZED, *width as f32, *height as f32)
                    }
                    LifecycleNotice::SurfaceLost { .. } => (SDL_EVENT_WINDOW_HIDDEN, 0.0, 0.0),
                    LifecycleNotice::SurfaceRevoked { .. } => {
                        (SDL_EVENT_WINDOW_DESTROYED, 0.0, 0.0)
                    }
                    LifecycleNotice::Paused { .. } => (SDL_EVENT_WINDOW_MINIMIZED, 0.0, 0.0),
                    LifecycleNotice::Resumed { .. } => (SDL_EVENT_WINDOW_RESTORED, 0.0, 0.0),
                };
                Sdl3EventPacket {
                    event_type,
                    timestamp_ns,
                    code: 0,
                    keycode: 0,
                    modifiers: 0,
                    x: nx,
                    y: ny,
                    dx: 0.0,
                    dy: 0.0,
                }
            }
        }
    }
}

/// SDL3 platform backend (implemented event/window bridge; external `libSDL3.so` optional).
pub struct Sdl3PlatformBackend {
    tracker: Mutex<Sdl3StateTracker>,
}

impl Default for Sdl3PlatformBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl Sdl3PlatformBackend {
    /// Creates the SDL3 platform backend.
    pub fn new() -> Self {
        Self {
            tracker: Mutex::new(Sdl3StateTracker::new()),
        }
    }

    /// Polls and translates the next event into an [`Sdl3EventPacket`].
    pub fn poll_sdl3_packet(&self, shared: &Shared) -> Result<Option<Sdl3EventPacket>> {
        let info = self.window_info(shared)?;
        let next = shared.input_queue().pop();
        Ok(next.map(|ev| {
            let mut tracker = self
                .tracker
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            tracker.translate(&ev, info.width, info.height)
        }))
    }
}

impl PlatformBackend for Sdl3PlatformBackend {
    fn kind(&self) -> PlatformKind {
        PlatformKind::Sdl3
    }

    fn name(&self) -> &'static str {
        "sdl3"
    }

    fn status(&self) -> BackendStatus {
        BackendStatus::Implemented
    }

    fn remaining_work(&self) -> Option<&'static str> {
        None
    }

    fn api_surface(&self) -> &'static str {
        "SDL3 (SDL_CreateWindowWithProperties + SDL_EVENT_* translator) for Minecraft 26.3+ & Horizon"
    }

    fn window_info(&self, shared: &Shared) -> Result<WindowInfo> {
        let backend = shared.backend()?;
        let stats = backend.stats();
        let size = backend.window_size();
        Ok(WindowInfo {
            width: size.width,
            height: size.height,
            has_surface: stats.has_window,
            binding_generation: backend.binding_generation(),
        })
    }

    fn queue_stats(&self, shared: &Shared) -> Result<QueueStats> {
        Ok(shared.queue_stats())
    }

    fn poll_event(&self, shared: &Shared) -> Result<Option<InputEvent>> {
        Ok(shared.input_queue().pop())
    }

    fn push_event(&self, shared: &Shared, event: InputEvent) -> Result<bool> {
        Ok(shared.input_queue().push(event))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::event::DeviceKind;
    use crate::input::sdl_tables as sdl;

    #[test]
    fn sdl3_backend_is_implemented_for_26_3() {
        let backend = Sdl3PlatformBackend::new();
        assert_eq!(backend.kind(), PlatformKind::Sdl3);
        assert!(backend.status().is_implemented());
        assert!(backend.remaining_work().is_none());
        assert!(backend.api_surface().contains("26.3"));
    }

    #[test]
    fn translates_touch_to_normalized_sdl3_finger_events_with_deltas() {
        let mut tracker = Sdl3StateTracker::new();
        let down = InputEvent::Touch {
            pointer_id: 1,
            phase: TouchPhase::Down,
            x: 540.0,
            y: 1200.0,
            pressure: 0.8,
            timestamp_ms: 100,
        };
        let pkt1 = tracker.translate(&down, 1080, 2400);
        assert_eq!(pkt1.event_type, SDL_EVENT_FINGER_DOWN);
        assert!((pkt1.x - 0.5).abs() < 1e-5);
        assert!((pkt1.y - 0.5).abs() < 1e-5);
        assert_eq!(pkt1.dx, 0.0);

        let mv = InputEvent::Touch {
            pointer_id: 1,
            phase: TouchPhase::Move,
            x: 810.0,
            y: 1800.0,
            pressure: 0.8,
            timestamp_ms: 116,
        };
        let pkt2 = tracker.translate(&mv, 1080, 2400);
        assert_eq!(pkt2.event_type, SDL_EVENT_FINGER_MOTION);
        assert!((pkt2.dx - 0.25).abs() < 1e-5);
        assert!((pkt2.dy - 0.25).abs() < 1e-5);
    }

    #[test]
    fn translates_key_and_lifecycle_events_to_sdl3() {
        let mut tracker = Sdl3StateTracker::new();
        let key = InputEvent::Key {
            pressed: true,
            repeat: false,
            scancode: sdl::SDL_SCANCODE_A,
            keycode: 'a' as u32,
            modifiers: sdl::SDL_KMOD_LSHIFT,
            android_keycode: 29,
            device: DeviceKind::Keyboard,
            timestamp_ms: 50,
        };
        let pkt = tracker.translate(&key, 1920, 1080);
        assert_eq!(pkt.event_type, SDL_EVENT_KEY_DOWN);
        assert_eq!(pkt.code, sdl::SDL_SCANCODE_A as i32);
        assert_eq!(pkt.modifiers, sdl::SDL_KMOD_LSHIFT);
    }
}