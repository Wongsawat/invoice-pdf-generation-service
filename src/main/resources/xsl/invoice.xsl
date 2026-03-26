<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:fo="http://www.w3.org/1999/XSL/Format">

    <!-- Thai e-Tax Invoice XSL-FO Template -->
    <!-- Transforms invoice JSON (converted to XML) to PDF layout -->

    <xsl:output method="xml" indent="yes"/>

    <!-- Page dimensions: A4 -->
    <xsl:variable name="page-width">210mm</xsl:variable>
    <xsl:variable name="page-height">297mm</xsl:variable>
    <xsl:variable name="margin">15mm</xsl:variable>

    <!-- Font settings -->
    <xsl:variable name="font-family">NotoSansThaiLooped, Helvetica, sans-serif</xsl:variable>
    <xsl:variable name="font-size">11pt</xsl:variable>
    <xsl:variable name="font-size-small">9pt</xsl:variable>
    <xsl:variable name="font-size-large">14pt</xsl:variable>
    <xsl:variable name="font-size-title">18pt</xsl:variable>

    <!-- Root template -->
    <xsl:template match="/invoice">
        <fo:root>
            <!-- Layout master set -->
            <fo:layout-master-set>
                <fo:simple-page-master master-name="invoice-page"
                    page-width="{$page-width}"
                    page-height="{$page-height}"
                    margin-top="{$margin}"
                    margin-bottom="{$margin}"
                    margin-left="{$margin}"
                    margin-right="{$margin}">
                    <fo:region-body margin-top="20mm" margin-bottom="20mm"/>
                    <fo:region-before extent="20mm"/>
                    <fo:region-after extent="20mm"/>
                </fo:simple-page-master>
            </fo:layout-master-set>

            <!-- Page sequence -->
            <fo:page-sequence master-reference="invoice-page">
                <!-- Header -->
                <fo:static-content flow-name="xsl-region-before">
                    <xsl:call-template name="header"/>
                </fo:static-content>

                <!-- Footer -->
                <fo:static-content flow-name="xsl-region-after">
                    <xsl:call-template name="footer"/>
                </fo:static-content>

                <!-- Body -->
                <fo:flow flow-name="xsl-region-body">
                    <xsl:call-template name="invoice-title"/>
                    <xsl:call-template name="parties-info"/>
                    <xsl:call-template name="invoice-details"/>
                    <xsl:call-template name="line-items"/>
                    <xsl:call-template name="totals"/>
                    <xsl:call-template name="payment-info"/>
                    <xsl:call-template name="notes"/>
                </fo:flow>
            </fo:page-sequence>
        </fo:root>
    </xsl:template>

    <!-- Header template -->
    <xsl:template name="header">
        <fo:block font-family="{$font-family}" font-size="{$font-size-small}" color="#666666"
            border-bottom="0.5pt solid #cccccc" padding-bottom="2mm">
            <fo:table width="100%" table-layout="fixed">
                <fo:table-column column-width="50%"/>
                <fo:table-column column-width="50%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell>
                            <fo:block text-align="left">
                                <xsl:value-of select="seller/name"/>
                            </fo:block>
                        </fo:table-cell>
                        <fo:table-cell>
                            <fo:block text-align="right">
                                e-Tax Invoice / ใบแจ้งหนี้อิเล็กทรอนิกส์
                            </fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>
        </fo:block>
    </xsl:template>

    <!-- Footer template -->
    <xsl:template name="footer">
        <fo:block font-family="{$font-family}" font-size="{$font-size-small}" color="#666666"
            border-top="0.5pt solid #cccccc" padding-top="2mm">
            <fo:table width="100%" table-layout="fixed">
                <fo:table-column column-width="33%"/>
                <fo:table-column column-width="34%"/>
                <fo:table-column column-width="33%"/>
                <fo:table-body>
                    <fo:table-row>
                        <fo:table-cell>
                            <fo:block text-align="left">
                                เอกสารนี้จัดทำด้วยระบบคอมพิวเตอร์
                            </fo:block>
                        </fo:table-cell>
                        <fo:table-cell>
                            <fo:block text-align="center">
                                หน้า <fo:page-number/> / <fo:page-number-citation ref-id="last-page"/>
                            </fo:block>
                        </fo:table-cell>
                        <fo:table-cell>
                            <fo:block text-align="right">
                                <xsl:value-of select="invoiceNumber"/>
                            </fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </fo:table-body>
            </fo:table>
        </fo:block>
    </xsl:template>

    <!-- Invoice title -->
    <xsl:template name="invoice-title">
        <fo:block font-family="{$font-family}" font-size="{$font-size-title}" font-weight="bold"
            text-align="center" space-after="5mm" color="#333333">
            ใบแจ้งหนี้ / INVOICE
        </fo:block>

        <!-- Document type indicator -->
        <fo:block font-family="{$font-family}" font-size="{$font-size}" text-align="center"
            space-after="10mm" color="#666666">
            (ต้นฉบับ / Original)
        </fo:block>
    </xsl:template>

    <!-- Parties information (Seller and Buyer) -->
    <xsl:template name="parties-info">
        <fo:table width="100%" table-layout="fixed" space-after="8mm">
            <fo:table-column column-width="50%"/>
            <fo:table-column column-width="50%"/>
            <fo:table-body>
                <fo:table-row>
                    <!-- Seller info -->
                    <fo:table-cell padding-right="5mm">
                        <fo:block font-family="{$font-family}" font-size="{$font-size}"
                            background-color="#f5f5f5" padding="3mm" border="0.5pt solid #dddddd">
                            <fo:block font-weight="bold" space-after="2mm">ผู้ขาย / Seller</fo:block>
                            <fo:block><xsl:value-of select="seller/name"/></fo:block>
                            <fo:block><xsl:value-of select="seller/address"/></fo:block>
                            <fo:block>
                                เลขประจำตัวผู้เสียภาษี: <xsl:value-of select="seller/taxId"/>
                            </fo:block>
                            <xsl:if test="seller/branchId">
                                <fo:block>
                                    สาขา: <xsl:value-of select="seller/branchId"/>
                                    <xsl:if test="seller/branchName">
                                        (<xsl:value-of select="seller/branchName"/>)
                                    </xsl:if>
                                </fo:block>
                            </xsl:if>
                            <xsl:if test="seller/phone">
                                <fo:block>โทร: <xsl:value-of select="seller/phone"/></fo:block>
                            </xsl:if>
                            <xsl:if test="seller/email">
                                <fo:block>อีเมล: <xsl:value-of select="seller/email"/></fo:block>
                            </xsl:if>
                        </fo:block>
                    </fo:table-cell>

                    <!-- Buyer info -->
                    <fo:table-cell padding-left="5mm">
                        <fo:block font-family="{$font-family}" font-size="{$font-size}"
                            background-color="#f5f5f5" padding="3mm" border="0.5pt solid #dddddd">
                            <fo:block font-weight="bold" space-after="2mm">ผู้ซื้อ / Buyer</fo:block>
                            <fo:block><xsl:value-of select="buyer/name"/></fo:block>
                            <fo:block><xsl:value-of select="buyer/address"/></fo:block>
                            <fo:block>
                                เลขประจำตัวผู้เสียภาษี: <xsl:value-of select="buyer/taxId"/>
                            </fo:block>
                            <xsl:if test="buyer/branchId">
                                <fo:block>
                                    สาขา: <xsl:value-of select="buyer/branchId"/>
                                    <xsl:if test="buyer/branchName">
                                        (<xsl:value-of select="buyer/branchName"/>)
                                    </xsl:if>
                                </fo:block>
                            </xsl:if>
                            <xsl:if test="buyer/phone">
                                <fo:block>โทร: <xsl:value-of select="buyer/phone"/></fo:block>
                            </xsl:if>
                            <xsl:if test="buyer/email">
                                <fo:block>อีเมล: <xsl:value-of select="buyer/email"/></fo:block>
                            </xsl:if>
                        </fo:block>
                    </fo:table-cell>
                </fo:table-row>
            </fo:table-body>
        </fo:table>
    </xsl:template>

    <!-- Invoice details -->
    <xsl:template name="invoice-details">
        <fo:table width="100%" table-layout="fixed" space-after="8mm"
            font-family="{$font-family}" font-size="{$font-size}">
            <fo:table-column column-width="25%"/>
            <fo:table-column column-width="25%"/>
            <fo:table-column column-width="25%"/>
            <fo:table-column column-width="25%"/>
            <fo:table-body>
                <fo:table-row>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                        <fo:block font-weight="bold">เลขที่เอกสาร</fo:block>
                        <fo:block font-size="{$font-size-small}">Invoice No.</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                        <fo:block><xsl:value-of select="invoiceNumber"/></fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                        <fo:block font-weight="bold">วันที่</fo:block>
                        <fo:block font-size="{$font-size-small}">Date</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                        <fo:block><xsl:value-of select="invoiceDate"/></fo:block>
                    </fo:table-cell>
                </fo:table-row>
                <xsl:if test="purchaseOrderNumber">
                    <fo:table-row>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                            <fo:block font-weight="bold">เลขที่ใบสั่งซื้อ</fo:block>
                            <fo:block font-size="{$font-size-small}">PO No.</fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block><xsl:value-of select="purchaseOrderNumber"/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                            <fo:block font-weight="bold">วันครบกำหนดชำระ</fo:block>
                            <fo:block font-size="{$font-size-small}">Due Date</fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block><xsl:value-of select="dueDate"/></fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </xsl:if>
            </fo:table-body>
        </fo:table>
    </xsl:template>

    <!-- Line items table -->
    <xsl:template name="line-items">
        <fo:table width="100%" table-layout="fixed" space-after="5mm"
            font-family="{$font-family}" font-size="{$font-size}">
            <fo:table-column column-width="8%"/>   <!-- No. -->
            <fo:table-column column-width="37%"/>  <!-- Description -->
            <fo:table-column column-width="12%"/>  <!-- Quantity -->
            <fo:table-column column-width="10%"/>  <!-- Unit -->
            <fo:table-column column-width="15%"/>  <!-- Unit Price -->
            <fo:table-column column-width="18%"/>  <!-- Amount -->

            <!-- Table header -->
            <fo:table-header>
                <fo:table-row background-color="#4a4a4a" color="white" font-weight="bold">
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block text-align="center">ลำดับ</fo:block>
                        <fo:block text-align="center" font-size="{$font-size-small}">No.</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block>รายการ</fo:block>
                        <fo:block font-size="{$font-size-small}">Description</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block text-align="right">จำนวน</fo:block>
                        <fo:block text-align="right" font-size="{$font-size-small}">Qty</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block text-align="center">หน่วย</fo:block>
                        <fo:block text-align="center" font-size="{$font-size-small}">Unit</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block text-align="right">ราคา/หน่วย</fo:block>
                        <fo:block text-align="right" font-size="{$font-size-small}">Unit Price</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                        <fo:block text-align="right">จำนวนเงิน</fo:block>
                        <fo:block text-align="right" font-size="{$font-size-small}">Amount</fo:block>
                    </fo:table-cell>
                </fo:table-row>
            </fo:table-header>

            <!-- Table body -->
            <fo:table-body>
                <xsl:for-each select="lineItems/item">
                    <fo:table-row>
                        <xsl:attribute name="background-color">
                            <xsl:choose>
                                <xsl:when test="position() mod 2 = 0">#f9f9f9</xsl:when>
                                <xsl:otherwise>white</xsl:otherwise>
                            </xsl:choose>
                        </xsl:attribute>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="center"><xsl:value-of select="position()"/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block><xsl:value-of select="description"/></fo:block>
                            <xsl:if test="itemCode">
                                <fo:block font-size="{$font-size-small}" color="#666666">
                                    รหัส: <xsl:value-of select="itemCode"/>
                                </fo:block>
                            </xsl:if>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="right">
                                <xsl:value-of select="format-number(quantity, '#,##0.00')"/>
                            </fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="center"><xsl:value-of select="unit"/></fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="right">
                                <xsl:value-of select="format-number(unitPrice, '#,##0.00')"/>
                            </fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="right">
                                <xsl:value-of select="format-number(amount, '#,##0.00')"/>
                            </fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </xsl:for-each>
            </fo:table-body>
        </fo:table>
    </xsl:template>

    <!-- Totals section -->
    <xsl:template name="totals">
        <fo:table width="100%" table-layout="fixed" space-after="8mm"
            font-family="{$font-family}" font-size="{$font-size}">
            <fo:table-column column-width="60%"/>
            <fo:table-column column-width="22%"/>
            <fo:table-column column-width="18%"/>
            <fo:table-body>
                <!-- Subtotal -->
                <fo:table-row>
                    <fo:table-cell><fo:block></fo:block></fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                        <fo:block text-align="right">รวมเงิน / Subtotal</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                        <fo:block text-align="right">
                            <xsl:value-of select="format-number(subtotal, '#,##0.00')"/>
                        </fo:block>
                    </fo:table-cell>
                </fo:table-row>

                <!-- Discount (if any) -->
                <xsl:if test="discount and discount != 0">
                    <fo:table-row>
                        <fo:table-cell><fo:block></fo:block></fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                            <fo:block text-align="right">ส่วนลด / Discount</fo:block>
                        </fo:table-cell>
                        <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                            <fo:block text-align="right" color="red">
                                -<xsl:value-of select="format-number(discount, '#,##0.00')"/>
                            </fo:block>
                        </fo:table-cell>
                    </fo:table-row>
                </xsl:if>

                <!-- Amount before VAT -->
                <fo:table-row>
                    <fo:table-cell><fo:block></fo:block></fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                        <fo:block text-align="right">มูลค่าก่อนภาษี / Amount before VAT</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                        <fo:block text-align="right">
                            <xsl:value-of select="format-number(amountBeforeVat, '#,##0.00')"/>
                        </fo:block>
                    </fo:table-cell>
                </fo:table-row>

                <!-- VAT -->
                <fo:table-row>
                    <fo:table-cell><fo:block></fo:block></fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                        <fo:block text-align="right">
                            ภาษีมูลค่าเพิ่ม <xsl:value-of select="vatRate"/>% / VAT
                        </fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                        <fo:block text-align="right">
                            <xsl:value-of select="format-number(vatAmount, '#,##0.00')"/>
                        </fo:block>
                    </fo:table-cell>
                </fo:table-row>

                <!-- Grand Total -->
                <fo:table-row font-weight="bold" font-size="{$font-size-large}">
                    <fo:table-cell><fo:block></fo:block></fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333" background-color="#4a4a4a" color="white">
                        <fo:block text-align="right">ยอดรวมทั้งสิ้น / Grand Total</fo:block>
                    </fo:table-cell>
                    <fo:table-cell padding="3mm" border="0.5pt solid #333333" background-color="#f0f0f0">
                        <fo:block text-align="right">
                            <xsl:value-of select="format-number(grandTotal, '#,##0.00')"/>
                        </fo:block>
                    </fo:table-cell>
                </fo:table-row>
            </fo:table-body>
        </fo:table>

        <!-- Amount in words -->
        <xsl:if test="amountInWords">
            <fo:block font-family="{$font-family}" font-size="{$font-size}" space-after="5mm"
                padding="3mm" background-color="#fffde7" border="0.5pt solid #ffc107">
                <fo:inline font-weight="bold">จำนวนเงินเป็นตัวอักษร: </fo:inline>
                <xsl:value-of select="amountInWords"/>
            </fo:block>
        </xsl:if>
    </xsl:template>

    <!-- Payment information -->
    <xsl:template name="payment-info">
        <xsl:if test="paymentInfo">
            <fo:block font-family="{$font-family}" font-size="{$font-size}" space-after="5mm"
                padding="3mm" border="0.5pt solid #dddddd">
                <fo:block font-weight="bold" space-after="2mm">ข้อมูลการชำระเงิน / Payment Information</fo:block>
                <xsl:if test="paymentInfo/method">
                    <fo:block>วิธีการชำระ: <xsl:value-of select="paymentInfo/method"/></fo:block>
                </xsl:if>
                <xsl:if test="paymentInfo/bankName">
                    <fo:block>ธนาคาร: <xsl:value-of select="paymentInfo/bankName"/></fo:block>
                </xsl:if>
                <xsl:if test="paymentInfo/accountNumber">
                    <fo:block>เลขที่บัญชี: <xsl:value-of select="paymentInfo/accountNumber"/></fo:block>
                </xsl:if>
                <xsl:if test="paymentInfo/accountName">
                    <fo:block>ชื่อบัญชี: <xsl:value-of select="paymentInfo/accountName"/></fo:block>
                </xsl:if>
            </fo:block>
        </xsl:if>
    </xsl:template>

    <!-- Notes section -->
    <xsl:template name="notes">
        <xsl:if test="notes">
            <fo:block font-family="{$font-family}" font-size="{$font-size-small}" space-after="5mm"
                padding="3mm" background-color="#f5f5f5" border="0.5pt solid #dddddd">
                <fo:block font-weight="bold" space-after="2mm">หมายเหตุ / Notes</fo:block>
                <fo:block><xsl:value-of select="notes"/></fo:block>
            </fo:block>
        </xsl:if>

        <!-- End marker for page counting -->
        <fo:block id="last-page"/>
    </xsl:template>

</xsl:stylesheet>
