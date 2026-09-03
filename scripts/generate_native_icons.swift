// SPDX-License-Identifier: MIT
import AppKit
import Foundation

private let size = 1024
private let output = URL(
  fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "OpenDistress-AppIcon.png")

guard
  let context = CGContext(
    data: nil,
    width: size,
    height: size,
    bitsPerComponent: 8,
    bytesPerRow: size * 4,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
  )
else {
  fatalError("Could not create icon graphics context")
}

context.setAllowsAntialiasing(true)
context.setShouldAntialias(true)
context.setFillColor(CGColor(red: 16.0 / 255.0, green: 24.0 / 255.0, blue: 32.0 / 255.0, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))

context.setStrokeColor(
  CGColor(red: 244.0 / 255.0, green: 240.0 / 255.0, blue: 230.0 / 255.0, alpha: 1))
context.setLineWidth(88)
context.setLineCap(.round)
context.addArc(
  center: CGPoint(x: 512, y: 547),
  radius: 368,
  startAngle: -51.7 * .pi / 180,
  endAngle: 231.7 * .pi / 180,
  clockwise: false
)
context.strokePath()

context.setFillColor(
  CGColor(red: 245.0 / 255.0, green: 166.0 / 255.0, blue: 35.0 / 255.0, alpha: 1))
context.fillEllipse(in: CGRect(x: 444, y: 146, width: 136, height: 136))

guard let image = context.makeImage() else {
  fatalError("Could not create icon image")
}
let bitmap = NSBitmapImageRep(cgImage: image)

guard let data = bitmap.representation(using: .png, properties: [:]) else {
  fatalError("Could not encode icon PNG")
}
try data.write(to: output, options: .atomic)
