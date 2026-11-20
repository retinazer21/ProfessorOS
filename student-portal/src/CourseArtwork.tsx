import type { Course } from "./academic";
import courseArtwork from "./assets/studio-courses.jpg";

const artworkPosition = [[0, 0], [960, 0], [0, 480], [960, 480]];

export function CourseArtwork({ course, className = "" }: { course: Course; className?: string }) {
    const position = artworkPosition[parseInt(course.id.slice(0, 2), 16) % artworkPosition.length];
    if (!position) return null;
    const [x, y] = position;
    return (
        <svg className={`studio-art ${className}`} aria-hidden="true"
            viewBox={`${x} ${y} 960 480`} preserveAspectRatio="xMidYMid slice">
            <image href={courseArtwork} width="1920" height="960" />
        </svg>
    );
}
