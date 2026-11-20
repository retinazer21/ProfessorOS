import { AppearanceGallery } from './appearance';
import { AuthForm } from './auth/AuthForm';
import { useAuth } from './auth/AuthProvider';
import {
    ArrowRight,
    CircleHelp,
    Fingerprint,
    GraduationCap,
    LockKeyhole,
    ShieldCheck,
} from "lucide-react";
import { student } from "./demo";
import { Brand, PageIntro, Panel, SectionHeading } from "./ui";

export function Profile() {
    const { access, logout } = useAuth();
    return (
        <>
            <PageIntro
                eyebrow="YOUR SPACE"
                title="Profile & security"
                description="A preview of the personal and account settings experience."
            />
            <AppearanceGallery />
            <div className="profile-layout">
                <Panel className="profile-card">
                    <div className="profile-banner">
                        <span className="profile-avatar">
                            {student.initials}
                        </span>
                    </div>
                    <h2>{student.name}</h2>
                    <p>{student.program}</p>
                    <span className="profile-chip">{student.year}</span>
                    <div className="profile-divider" />
                    <dl>
                        <div>
                            <dt>Student ID</dt>
                            <dd>{student.id}</dd>
                        </div>
                        <div>
                            <dt>University</dt>
                            <dd>{student.university}</dd>
                        </div>
                        <div>
                            <dt>Email</dt>
                            <dd>{student.email}</dd>
                        </div>
                    </dl>
                    <p className="muted-note">
                        Fictional profile · changes are unavailable in this
                        visual prototype.
                    </p>
                </Panel>
                <div className="profile-stack">
                    <Panel>
                        <SectionHeading
                            label="ACCOUNT"
                            title="Account security"
                        />
                        <div className="security-row">
                            <span className="stat-icon violet">
                                <LockKeyhole size={19} />
                            </span>
                            <div>
                                <strong>Sign-in experience</strong>
                                <p>
                                    {access.email} · {access.role}
                                </p>
                            </div>
                            <button className="button-secondary" onClick={() => void logout()}>Sign out</button>
                        </div>
                        <div className="security-row">
                            <span className="stat-icon blue">
                                <Fingerprint size={19} />
                            </span>
                            <div>
                                <strong>Multi-factor verification</strong>
                                <p>
                                    Authenticator verification is required before workspace access.
                                </p>
                            </div>
                            <span className="profile-chip">Verified</span>
                        </div>
                    </Panel>
                    <Panel className="privacy-panel">
                        <ShieldCheck size={24} />
                        <div>
                            <h2>Built for a clear boundary</h2>
                            <p>
                                Preview panels contain fictional academic data.
                                Published grades are loaded separately from the secure portal after MFA.
                            </p>
                        </div>
                    </Panel>
                </div>
            </div>
        </>
    );
}

export function AuthScreen({ mfa }: { mfa: boolean }) {
    const { error } = useAuth();
    return (
        <div className="auth-page">
            <div className="auth-form-side">
                <Brand />
                <div className="auth-center">
                    <span className="eyebrow">
                        UNIVERSITY ACCOUNT
                    </span>
                    <h1>
                        {mfa ? "One more step." : "Your learning space awaits."}
                    </h1>
                    <p className="auth-lede">
                        {mfa
                            ? "Verify with your authenticator to access your workspace."
                            : "A quiet place to follow your academic journey. Sign in with your university account."}
                    </p>
                    {error && <p role="alert">{error}</p>}
                    <AuthForm mfa={mfa} />
                    <p className="auth-disclaimer">
                        <ShieldCheck size={15} /> Managed authentication · MFA required
                    </p>
                </div>
                <AppearanceGallery />
                <span className="auth-footer">
                    © 2026 Professor OS · Student Portal concept
                </span>
            </div>
            <div className="auth-art-side">
                <div className="auth-art-rings" aria-hidden="true">
                    <div />
                    <div />
                    <div />
                    <span>
                        <GraduationCap size={95} strokeWidth={1} />
                    </span>
                </div>
                <div className="auth-art-copy">
                    <span className="eyebrow">
                        PROFESSOR OS / STUDENT SPACE
                    </span>
                    <h2>
                        Make room for
                        <br />
                        <em>what's next.</em>
                    </h2>
                    <p>
                        One beautiful view of your courses, progress, and
                        possibilities.
                    </p>
                </div>
                <span className="auth-art-index">01 — 04</span>
            </div>
        </div>
    );
}

export function NotFound() {
    return (
        <div className="state-card">
            <span className="state-icon">
                <CircleHelp size={25} />
            </span>
            <h1>Page not found</h1>
            <p>This preview page doesn’t exist.</p>
            <a className="button-secondary" href="#/dashboard">
                Back to overview <ArrowRight size={16} />
            </a>
        </div>
    );
}
