import CaveKit
import CoreImage
import Foundation

extension LabelFrame {
    /// The photo cut to the label the AI found in it (CaveKit's `WineLabelFrame`),
    /// turned so its text is level, with a margin around it. Nil when too small to use.
    static func framed(_ photo: CIImage, on frame: WineLabelFrame) -> CIImage? {
        let extent = photo.extent
        let crop = frame.crop(width: Double(extent.width), height: Double(extent.height))
        // The frame is measured from the top left, Core Image from the bottom left.
        let center = CGPoint(x: extent.minX + CGFloat(crop.centerX), y: extent.maxY - CGFloat(crop.centerY))
        let rect = CGRect(x: center.x - CGFloat(crop.width) / 2, y: center.y - CGFloat(crop.height) / 2,
                          width: CGFloat(crop.width), height: CGFloat(crop.height))
        guard rect.width >= 8, rect.height >= 8 else { return nil }
        // Text running uphill is turned clockwise (a negative angle here) to be level.
        let turn = CGAffineTransform(translationX: center.x, y: center.y)
            .rotated(by: CGFloat(-frame.angle * .pi / 180))
            .translatedBy(x: -center.x, y: -center.y)
        let framed = photo.transformed(by: turn).cropped(to: rect)
        return framed.extent.isEmpty ? nil : framed
    }
}
